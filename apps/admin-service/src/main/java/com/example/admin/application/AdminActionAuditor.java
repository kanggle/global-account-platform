package com.example.admin.application;

import com.example.admin.application.event.AdminEventPublisher;
import com.example.admin.application.exception.AuditFailureException;
import com.example.admin.infrastructure.persistence.AdminActionJpaEntity;
import com.example.admin.infrastructure.persistence.AdminActionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes the append-only audit row for an admin command, plus the admin.action.performed
 * outbox event, in a single transaction. fail-closed: if the insert fails, the command
 * is aborted ({@link AuditFailureException}) — no downstream side effects may be accepted
 * without an audit row.
 *
 * <p>Because {@code admin_actions} is append-only (DB trigger blocks UPDATE/DELETE),
 * we pre-reserve an ID, invoke the downstream, and then write a single row with the
 * final outcome in the same transaction as the outbox event. This matches A7 (single
 * transaction for audit + outbox) and A3 (immutable audit rows).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminActionAuditor {

    private final AdminActionJpaRepository repository;
    private final AdminEventPublisher eventPublisher;

    public String reserveAuditId() {
        return UUID.randomUUID().toString();
    }

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
