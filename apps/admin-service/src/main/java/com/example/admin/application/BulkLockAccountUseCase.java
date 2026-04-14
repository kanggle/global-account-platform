package com.example.admin.application;

import com.example.admin.application.exception.AuditFailureException;
import com.example.admin.application.exception.BatchSizeExceededException;
import com.example.admin.application.exception.DownstreamFailureException;
import com.example.admin.application.exception.IdempotencyKeyConflictException;
import com.example.admin.application.exception.NonRetryableDownstreamException;
import com.example.admin.application.exception.ReasonRequiredException;
import com.example.admin.infrastructure.persistence.BulkLockIdempotencyJpaEntity;
import com.example.admin.infrastructure.persistence.BulkLockIdempotencyJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Bulk-lock orchestrator. Dedupes input, enforces the batch cap, delegates to
 * {@link AccountAdminUseCase#lock} for each accountId, and applies the
 * (operator, idempotency-key) replay contract persisted in
 * {@link BulkLockIdempotencyJpaRepository}.
 *
 * <p>Per-row outcomes:
 * <ul>
 *   <li>{@code LOCKED} — 200 from account-service</li>
 *   <li>{@code NOT_FOUND} — non-retryable 404</li>
 *   <li>{@code ALREADY_LOCKED} — non-retryable 400/409 with STATE_TRANSITION_INVALID</li>
 *   <li>{@code FAILURE} — any other error (5xx exhausted retries, circuit open, audit failure swallowed here, etc.)</li>
 * </ul>
 *
 * <p>Per-row failures are isolated: one account failing does not abort the
 * batch. The single {@code admin_actions} row per target is written by the
 * delegated {@link AccountAdminUseCase#lock} path, preserving the canonical
 * envelope and audit invariants established by TASK-BE-028b2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkLockAccountUseCase {

    public static final int MAX_BATCH_SIZE = 100;
    public static final int MIN_REASON_LENGTH = 8;

    public static final String OUTCOME_LOCKED = "LOCKED";
    public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    public static final String OUTCOME_ALREADY_LOCKED = "ALREADY_LOCKED";
    public static final String OUTCOME_FAILURE = "FAILURE";

    private final AccountAdminUseCase accountAdminUseCase;
    private final BulkLockIdempotencyJpaRepository idempotencyRepository;
    private final AdminOperatorJpaRepository operatorRepository;
    private final ObjectMapper objectMapper;

    public BulkLockAccountResult execute(BulkLockAccountCommand cmd) {
        validate(cmd);

        List<String> deduped = dedupe(cmd.accountIds());

        Long operatorPk = operatorRepository.findByOperatorId(cmd.operator().operatorId())
                .orElseThrow(() -> new AuditFailureException(
                        "admin_operators row not found for operatorId=" + cmd.operator().operatorId()))
                .getId();

        String requestHash = computeRequestHash(deduped, cmd.reason(), cmd.ticketId());

        // Idempotency check: identical request returns stored response with no
        // further side-effects. Divergent payload under same key → 409.
        var existing = idempotencyRepository.findById(
                new BulkLockIdempotencyJpaEntity.Key(operatorPk, cmd.idempotencyKey()));
        if (existing.isPresent()) {
            var row = existing.get();
            if (!row.getRequestHash().equals(requestHash)) {
                throw new IdempotencyKeyConflictException(
                        "Idempotency-Key reused with a different request payload");
            }
            return new BulkLockAccountResult(parseStoredResults(row.getResponseBody()), true);
        }

        // Execute sequentially; collect per-row outcomes.
        List<BulkLockAccountResult.Item> items = new ArrayList<>(deduped.size());
        for (String accountId : deduped) {
            items.add(processOne(cmd, accountId));
        }

        BulkLockAccountResult result = new BulkLockAccountResult(items, false);

        // Persist canonical response body for future replays.
        try {
            idempotencyRepository.save(BulkLockIdempotencyJpaEntity.create(
                    operatorPk,
                    cmd.idempotencyKey(),
                    requestHash,
                    serialiseResults(items),
                    Instant.now()));
        } catch (RuntimeException ex) {
            // Non-fatal: the command already executed and every account has its
            // own admin_actions row. Log and continue; a subsequent retry will
            // hit whichever side of the race wins.
            log.warn("Failed to persist bulk-lock idempotency record: operatorId={} key={}",
                    cmd.operator().operatorId(), cmd.idempotencyKey(), ex);
        }

        return result;
    }

    private BulkLockAccountResult.Item processOne(BulkLockAccountCommand cmd, String accountId) {
        String perRowIdempotency = cmd.idempotencyKey() + ":" + accountId;
        try {
            accountAdminUseCase.lock(new LockAccountCommand(
                    accountId, cmd.reason(), cmd.ticketId(), perRowIdempotency, cmd.operator()));
            return new BulkLockAccountResult.Item(accountId, OUTCOME_LOCKED, null, null);
        } catch (NonRetryableDownstreamException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains(" 404")) {
                return new BulkLockAccountResult.Item(accountId, OUTCOME_NOT_FOUND,
                        "ACCOUNT_NOT_FOUND", "Account does not exist");
            }
            if (msg.contains(" 409") || msg.contains(" 400")) {
                return new BulkLockAccountResult.Item(accountId, OUTCOME_ALREADY_LOCKED,
                        "STATE_TRANSITION_INVALID", "Account is not in a lockable state");
            }
            return new BulkLockAccountResult.Item(accountId, OUTCOME_FAILURE,
                    "DOWNSTREAM_ERROR", msg);
        } catch (DownstreamFailureException ex) {
            return new BulkLockAccountResult.Item(accountId, OUTCOME_FAILURE,
                    "DOWNSTREAM_ERROR", ex.getMessage());
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException ex) {
            return new BulkLockAccountResult.Item(accountId, OUTCOME_FAILURE,
                    "CIRCUIT_OPEN", "Downstream circuit is open");
        } catch (AuditFailureException ex) {
            // Audit path failed — fail-closed for this row but continue the batch.
            return new BulkLockAccountResult.Item(accountId, OUTCOME_FAILURE,
                    "AUDIT_FAILURE", "Audit write failed");
        } catch (RuntimeException ex) {
            log.warn("Unexpected error locking accountId={} in bulk batch", accountId, ex);
            return new BulkLockAccountResult.Item(accountId, OUTCOME_FAILURE,
                    "INTERNAL_ERROR", ex.getClass().getSimpleName());
        }
    }

    private void validate(BulkLockAccountCommand cmd) {
        if (cmd.reason() == null || cmd.reason().trim().length() < MIN_REASON_LENGTH) {
            throw new ReasonRequiredException();
        }
        if (cmd.accountIds() == null || cmd.accountIds().isEmpty()) {
            throw new IllegalArgumentException("accountIds must not be empty");
        }
        if (cmd.accountIds().size() > MAX_BATCH_SIZE) {
            throw new BatchSizeExceededException(
                    "Batch exceeds maximum of " + MAX_BATCH_SIZE + " accountIds");
        }
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
    }

    private static List<String> dedupe(List<String> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private String computeRequestHash(List<String> accountIds, String reason, String ticketId) {
        // Canonical representation: sorted dedup list + reason + ticketId.
        Map<String, Object> canonical = new LinkedHashMap<>();
        List<String> sorted = new ArrayList<>(accountIds);
        java.util.Collections.sort(sorted);
        canonical.put("accountIds", sorted);
        canonical.put("reason", reason);
        canonical.put("ticketId", ticketId);
        try {
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to compute request hash", e);
        }
    }

    private String serialiseResults(List<BulkLockAccountResult.Item> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise bulk-lock result", e);
        }
    }

    private List<BulkLockAccountResult.Item> parseStoredResults(String body) {
        try {
            return objectMapper.readValue(
                    body.getBytes(StandardCharsets.UTF_8),
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, BulkLockAccountResult.Item.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to parse stored bulk-lock result", e);
        }
    }
}
