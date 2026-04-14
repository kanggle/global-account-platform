package com.example.admin.application;

import com.example.admin.application.exception.BatchSizeExceededException;
import com.example.admin.application.exception.DownstreamFailureException;
import com.example.admin.application.exception.IdempotencyKeyConflictException;
import com.example.admin.application.exception.NonRetryableDownstreamException;
import com.example.admin.application.exception.ReasonRequiredException;
import com.example.admin.infrastructure.persistence.BulkLockIdempotencyJpaEntity;
import com.example.admin.infrastructure.persistence.BulkLockIdempotencyJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class BulkLockAccountUseCaseTest {

    @Mock AccountAdminUseCase accountAdminUseCase;
    @Mock BulkLockIdempotencyJpaRepository idempotencyRepository;
    @Mock AdminOperatorJpaRepository operatorRepository;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks BulkLockAccountUseCase useCase;

    private static OperatorContext operator() {
        return new OperatorContext("op-uuid-1", "jti-1");
    }

    private static LockAccountResult okResult(String accountId) {
        return new LockAccountResult(accountId, "ACTIVE", "LOCKED",
                "op-uuid-1", Instant.parse("2026-04-14T00:00:00Z"), "audit-" + accountId);
    }

    private void stubOperatorResolution() {
        AdminOperatorJpaEntity op = org.mockito.Mockito.mock(AdminOperatorJpaEntity.class);
        when(op.getId()).thenReturn(42L);
        when(operatorRepository.findByOperatorId("op-uuid-1")).thenReturn(Optional.of(op));
    }

    @Test
    void batch_over_100_throws_batch_size_exceeded() {
        List<String> ids = IntStream.range(0, 101).mapToObj(i -> "acc-" + i).collect(Collectors.toList());

        assertThatThrownBy(() -> useCase.execute(new BulkLockAccountCommand(
                ids, "fraud-wave", null, "idemp-1", operator())))
                .isInstanceOf(BatchSizeExceededException.class);

        verify(accountAdminUseCase, never()).lock(any());
    }

    @Test
    void batch_exactly_100_is_accepted() {
        stubOperatorResolution();
        List<String> ids = IntStream.range(0, 100).mapToObj(i -> "acc-" + i).collect(Collectors.toList());
        when(idempotencyRepository.findById(any())).thenReturn(Optional.empty());
        when(accountAdminUseCase.lock(any())).thenAnswer(inv -> {
            LockAccountCommand cmd = inv.getArgument(0);
            return okResult(cmd.accountId());
        });

        BulkLockAccountResult r = useCase.execute(new BulkLockAccountCommand(
                ids, "fraud-wave", null, "idemp-100", operator()));

        assertThat(r.results()).hasSize(100);
        verify(accountAdminUseCase, times(100)).lock(any());
    }

    @Test
    void reason_shorter_than_8_throws_reason_required() {
        assertThatThrownBy(() -> useCase.execute(new BulkLockAccountCommand(
                List.of("acc-1"), "short", null, "idemp", operator())))
                .isInstanceOf(ReasonRequiredException.class);
    }

    @Test
    void duplicate_account_ids_are_deduped_preserving_first_order() {
        stubOperatorResolution();
        when(idempotencyRepository.findById(any())).thenReturn(Optional.empty());
        when(accountAdminUseCase.lock(any())).thenAnswer(inv ->
                okResult(((LockAccountCommand) inv.getArgument(0)).accountId()));

        BulkLockAccountResult r = useCase.execute(new BulkLockAccountCommand(
                List.of("acc-1", "acc-2", "acc-1", "acc-3", "acc-2"),
                "fraud-wave-dedup", null, "idemp-dedup", operator()));

        assertThat(r.results()).extracting(BulkLockAccountResult.Item::accountId)
                .containsExactly("acc-1", "acc-2", "acc-3");
        verify(accountAdminUseCase, times(3)).lock(any());
    }

    @Test
    void per_row_failures_isolated_not_found_already_locked_failure_locked() {
        stubOperatorResolution();
        when(idempotencyRepository.findById(any())).thenReturn(Optional.empty());
        when(accountAdminUseCase.lock(any())).thenAnswer(inv -> {
            String id = ((LockAccountCommand) inv.getArgument(0)).accountId();
            return switch (id) {
                case "acc-ok" -> okResult(id);
                case "acc-404" -> throw new NonRetryableDownstreamException("account-service error 404", null);
                case "acc-409" -> throw new NonRetryableDownstreamException("account-service error 409", null);
                case "acc-500" -> throw new DownstreamFailureException("account-service error 500", null);
                default -> okResult(id);
            };
        });

        BulkLockAccountResult r = useCase.execute(new BulkLockAccountCommand(
                List.of("acc-ok", "acc-404", "acc-409", "acc-500"),
                "partial-failure-test", null, "idemp-partial", operator()));

        assertThat(r.results()).extracting(BulkLockAccountResult.Item::outcome)
                .containsExactly("LOCKED", "NOT_FOUND", "ALREADY_LOCKED", "FAILURE");
        assertThat(r.results().get(1).errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(r.results().get(2).errorCode()).isEqualTo("STATE_TRANSITION_INVALID");
        assertThat(r.results().get(3).errorCode()).isEqualTo("DOWNSTREAM_ERROR");
    }

    @Test
    void identical_retry_returns_stored_response_without_reexecuting() throws Exception {
        stubOperatorResolution();

        // Compute the canonical request hash exactly how the use-case does so
        // we can pre-seed a matching idempotency row and verify the replay path.
        List<String> sorted = new java.util.ArrayList<>(List.of("acc-a"));
        java.util.Collections.sort(sorted);
        java.util.Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("accountIds", sorted);
        canonical.put("reason", "replay-test-reason");
        canonical.put("ticketId", null);
        byte[] json = new ObjectMapper().writeValueAsBytes(canonical);
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(json));

        List<BulkLockAccountResult.Item> stored = List.of(
                new BulkLockAccountResult.Item("acc-a", "LOCKED", null, null));
        String storedBody = new ObjectMapper().writeValueAsString(stored);

        BulkLockIdempotencyJpaEntity existing = BulkLockIdempotencyJpaEntity.create(
                42L, "idemp-replay", hash, storedBody, Instant.now());
        when(idempotencyRepository.findById(any())).thenReturn(Optional.of(existing));

        BulkLockAccountResult replayed = useCase.execute(new BulkLockAccountCommand(
                List.of("acc-a"), "replay-test-reason", null, "idemp-replay", operator()));

        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.results()).hasSize(1);
        assertThat(replayed.results().get(0).outcome()).isEqualTo("LOCKED");
        verify(accountAdminUseCase, never()).lock(any());
        verify(idempotencyRepository, never()).save(any());
    }

    @Test
    void same_key_different_payload_raises_idempotency_key_conflict() {
        stubOperatorResolution();
        BulkLockIdempotencyJpaEntity existing = BulkLockIdempotencyJpaEntity.create(
                42L, "idemp-conflict", "deadbeef".repeat(8), "[]", Instant.now());
        when(idempotencyRepository.findById(any())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> useCase.execute(new BulkLockAccountCommand(
                List.of("acc-z"), "divergent-payload", null, "idemp-conflict", operator())))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        verify(accountAdminUseCase, never()).lock(any());
    }
}
