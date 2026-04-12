package com.example.admin.application;

import com.example.admin.application.event.AdminEventPublisher;
import com.example.admin.application.exception.AuditFailureException;
import com.example.admin.infrastructure.persistence.AdminActionJpaEntity;
import com.example.admin.infrastructure.persistence.AdminActionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes the audit row for an admin command in two phases to enforce
 * audit-before-downstream (A10 fail-closed):
 *
 * <ol>
 *   <li>{@link #recordStart(StartRecord)} — INSERT with {@code outcome=IN_PROGRESS}
 *       BEFORE the downstream HTTP call. If this fails, the command is aborted
 *       via {@link AuditFailureException} before any side effect occurs.</li>
 *   <li>{@link #recordCompletion(CompletionRecord)} — UPDATE outcome to SUCCESS/FAILURE
 *       and emit the {@code admin.action.performed} outbox event. Runs in a separate
 *       transaction so the start-row commit is durable even if downstream stalls.</li>
 * </ol>
 *
 * The DB trigger {@code trg_admin_actions_finalize_only} enforces that only a
 * row whose current outcome is {@code IN_PROGRESS} may be updated, and only on
 * the {@code outcome}, {@code downstream_detail}, and {@code completed_at} columns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminActionAuditor {

    private final AdminActionJpaRepository repository;
    private final AdminEventPublisher eventPublisher;

    public String newAuditId() {
        return UUID.randomUUID().toString();
    }

    /**
     * @deprecated Use {@link #newAuditId()} plus {@link #recordStart(StartRecord)}.
     *             Retained for backward compatibility with older callers/tests.
     */
    @Deprecated
    public String reserveAuditId() {
        return newAuditId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStart(StartRecord record) {
        try {
            AdminActionJpaEntity entity = AdminActionJpaEntity.create(
                    record.auditId(),
                    record.actionCode().name(),
                    record.operator().operatorId(),
                    primaryRole(record.operator()),
                    record.targetType(),
                    record.targetId(),
                    record.reason(),
                    record.ticketId(),
                    record.idempotencyKey(),
                    Outcome.IN_PROGRESS.name(),
                    null,
                    record.startedAt(),
                    null);
            repository.save(entity);
        } catch (RuntimeException ex) {
            log.error("Failed to write IN_PROGRESS admin_actions row (fail-closed): auditId={}",
                    record.auditId(), ex);
            throw new AuditFailureException("Failed to record admin action audit", ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCompletion(CompletionRecord record) {
        try {
            AdminActionJpaEntity entity = repository.findById(record.auditId())
                    .orElseThrow(() -> new AuditFailureException(
                            "IN_PROGRESS audit row not found for id=" + record.auditId()));
            entity.finalizeOutcome(
                    record.outcome().name(),
                    record.downstreamDetail(),
                    record.completedAt());
            repository.save(entity);
            eventPublisher.publishActionPerformed(record.toLegacyRecord());
        } catch (AuditFailureException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Failed to finalize admin_actions audit row: auditId={}",
                    record.auditId(), ex);
            throw new AuditFailureException("Failed to finalize admin action audit", ex);
        }
    }

    /**
     * Legacy single-shot audit write. Retained so that existing tests still compile;
     * new code paths should use {@link #recordStart} + {@link #recordCompletion}.
     */
    @Transactional
    public void record(AuditRecord record) {
        try {
            AdminActionJpaEntity entity = AdminActionJpaEntity.create(
                    record.auditId(),
                    record.actionCode().name(),
                    record.operator().operatorId(),
                    primaryRole(record.operator()),
                    record.targetType(),
                    record.targetId(),
                    record.reason(),
                    record.ticketId(),
                    record.idempotencyKey(),
                    record.outcome().name(),
                    record.downstreamDetail(),
                    record.startedAt(),
                    record.completedAt());
            repository.save(entity);
            eventPublisher.publishActionPerformed(record);
        } catch (RuntimeException ex) {
            log.error("Failed to write admin_actions audit row (fail-closed): auditId={}", record.auditId(), ex);
            throw new AuditFailureException("Failed to record admin action audit", ex);
        }
    }

    private static String primaryRole(OperatorContext ctx) {
        if (ctx.roles().contains(OperatorRole.SUPER_ADMIN)) return OperatorRole.SUPER_ADMIN.name();
        if (ctx.roles().contains(OperatorRole.ACCOUNT_ADMIN)) return OperatorRole.ACCOUNT_ADMIN.name();
        if (ctx.roles().contains(OperatorRole.AUDITOR)) return OperatorRole.AUDITOR.name();
        return "UNKNOWN";
    }

    public record StartRecord(
            String auditId,
            ActionCode actionCode,
            OperatorContext operator,
            String targetType,
            String targetId,
            String reason,
            String ticketId,
            String idempotencyKey,
            Instant startedAt
    ) {}

    public record CompletionRecord(
            String auditId,
            ActionCode actionCode,
            OperatorContext operator,
            String targetType,
            String targetId,
            String reason,
            String ticketId,
            String idempotencyKey,
            Outcome outcome,
            String downstreamDetail,
            Instant startedAt,
            Instant completedAt
    ) {
        AuditRecord toLegacyRecord() {
            return new AuditRecord(
                    auditId, actionCode, operator, targetType, targetId,
                    reason, ticketId, idempotencyKey,
                    outcome, downstreamDetail, startedAt, completedAt);
        }
    }

    public record AuditRecord(
            String auditId,
            ActionCode actionCode,
            OperatorContext operator,
            String targetType,
            String targetId,
            String reason,
            String ticketId,
            String idempotencyKey,
            Outcome outcome,
            String downstreamDetail,
            Instant startedAt,
            Instant completedAt
    ) {}
}
