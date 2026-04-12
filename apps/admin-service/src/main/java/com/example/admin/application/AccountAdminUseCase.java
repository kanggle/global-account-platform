package com.example.admin.application;

import com.example.admin.application.exception.DownstreamFailureException;
import com.example.admin.application.exception.PermissionDeniedException;
import com.example.admin.application.exception.ReasonRequiredException;
import com.example.admin.infrastructure.client.AccountServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Orchestrates lock/unlock operator commands. Each command:
 *   1. validates operator role + reason
 *   2. INSERTs an IN_PROGRESS audit row BEFORE the downstream call (A10 fail-closed)
 *   3. calls account-service internal HTTP (with Idempotency-Key)
 *   4. finalizes the audit row (SUCCESS or FAILURE) and emits the outbox event
 *
 * If the IN_PROGRESS insert fails {@link com.example.admin.application.exception.AuditFailureException}
 * propagates and no downstream call is made.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountAdminUseCase {

    private final AccountServiceClient accountServiceClient;
    private final AdminActionAuditor auditor;

    public LockAccountResult lock(LockAccountCommand cmd) {
        requireReason(cmd.reason());
        requireRole(cmd.operator(), OperatorRole.ACCOUNT_ADMIN);

        String auditId = auditor.newAuditId();
        Instant startedAt = Instant.now();

        auditor.recordStart(new AdminActionAuditor.StartRecord(
                auditId, ActionCode.ACCOUNT_LOCK, cmd.operator(),
                "account", cmd.accountId(),
                cmd.reason(), cmd.ticketId(), cmd.idempotencyKey(),
                startedAt));

        AccountServiceClient.LockResponse downstream;
        try {
            downstream = accountServiceClient.lock(
                    cmd.accountId(),
                    cmd.operator().operatorId(),
                    cmd.reason(),
                    cmd.ticketId(),
                    cmd.idempotencyKey());
        } catch (DownstreamFailureException ex) {
            auditor.recordCompletion(new AdminActionAuditor.CompletionRecord(
                    auditId, ActionCode.ACCOUNT_LOCK, cmd.operator(),
                    "account", cmd.accountId(),
                    cmd.reason(), cmd.ticketId(), cmd.idempotencyKey(),
                    Outcome.FAILURE, ex.getMessage(),
                    startedAt, Instant.now()));
            throw ex;
        }

        Instant completedAt = Instant.now();
        auditor.recordCompletion(new AdminActionAuditor.CompletionRecord(
                auditId, ActionCode.ACCOUNT_LOCK, cmd.operator(),
                "account", cmd.accountId(),
                cmd.reason(), cmd.ticketId(), cmd.idempotencyKey(),
                Outcome.SUCCESS, null, startedAt, completedAt));

        return new LockAccountResult(
                downstream.accountId(),
                downstream.previousStatus(),
                downstream.currentStatus(),
                cmd.operator().operatorId(),
                downstream.lockedAt() != null ? downstream.lockedAt() : completedAt,
                auditId);
    }

    public UnlockAccountResult unlock(UnlockAccountCommand cmd) {
        requireReason(cmd.reason());
        requireRole(cmd.operator(), OperatorRole.ACCOUNT_ADMIN);

        String auditId = auditor.newAuditId();
        Instant startedAt = Instant.now();

        auditor.recordStart(new AdminActionAuditor.StartRecord(
                auditId, ActionCode.ACCOUNT_UNLOCK, cmd.operator(),
                "account", cmd.accountId(),
                cmd.reason(), cmd.ticketId(), cmd.idempotencyKey(),
                startedAt));

        AccountServiceClient.LockResponse downstream;
        try {
            downstream = accountServiceClient.unlock(
                    cmd.accountId(),
                    cmd.operator().operatorId(),
                    cmd.reason(),
                    cmd.ticketId(),
                    cmd.idempotencyKey());
        } catch (DownstreamFailureException ex) {
            auditor.recordCompletion(new AdminActionAuditor.CompletionRecord(
                    auditId, ActionCode.ACCOUNT_UNLOCK, cmd.operator(),
                    "account", cmd.accountId(),
                    cmd.reason(), cmd.ticketId(), cmd.idempotencyKey(),
                    Outcome.FAILURE, ex.getMessage(),
                    startedAt, Instant.now()));
            throw ex;
        }

        Instant completedAt = Instant.now();
        auditor.recordCompletion(new AdminActionAuditor.CompletionRecord(
                auditId, ActionCode.ACCOUNT_UNLOCK, cmd.operator(),
                "account", cmd.accountId(),
                cmd.reason(), cmd.ticketId(), cmd.idempotencyKey(),
                Outcome.SUCCESS, null, startedAt, completedAt));

        return new UnlockAccountResult(
                downstream.accountId(),
                downstream.previousStatus(),
                downstream.currentStatus(),
                cmd.operator().operatorId(),
                downstream.unlockedAt() != null ? downstream.unlockedAt() : completedAt,
                auditId);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ReasonRequiredException();
        }
    }

    private static void requireRole(OperatorContext op, OperatorRole required) {
        if (op == null) {
            throw new PermissionDeniedException("operator context missing");
        }
        if (!op.hasRole(required)) {
            throw new PermissionDeniedException("operator lacks role " + required);
        }
    }
}
