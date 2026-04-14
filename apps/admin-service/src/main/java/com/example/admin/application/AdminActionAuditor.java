package com.example.admin.application;

import com.example.admin.application.event.AdminEventPublisher;
import com.example.admin.application.exception.AuditFailureException;
import com.example.admin.domain.rbac.Permission;
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
                    null, // operator_id BIGINT FK — resolution deferred to TASK-BE-028b2
                    permissionForActionCode(record.actionCode()), // TASK-BE-028a
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
            AdminActionJpaEntity entity = repository.findByLegacyAuditId(record.auditId())
                    .orElseThrow(() -> new AuditFailureException(
                            "IN_PROGRESS audit row not found for legacyAuditId=" + record.auditId()));
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
                    null, // operator_id BIGINT FK — resolution deferred to TASK-BE-028b2
                    permissionForActionCode(record.actionCode()),
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

    /**
     * TASK-BE-028a: Records a DENIED admin_actions row directly (no IN_PROGRESS phase).
     * Invoked by {@code RequiresPermissionAspect} when permission evaluation fails.
     *
     * <p>Writes in a REQUIRES_NEW transaction so that the deny audit row is durable
     * even if the controller request is rolled back. No outbox event is emitted in
     * this increment (envelope reshape deferred to TASK-BE-028b).
     */
    // TODO(TASK-BE-028b): emit admin.action.performed outbox event for DENIED rows
    //                    with the new actor/action/target/outcome envelope.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(String operatorId,
                             String permissionUsed,
                             String endpoint,
                             String method,
                             String targetDescription) {
        try {
            Instant now = Instant.now();
            String auditId = UUID.randomUUID().toString();
            String actionCode = actionCodeForPermission(permissionUsed);
            String targetType = targetDescription != null ? "TARGET" : "AUDIT_QUERY";
            String targetId = targetDescription != null ? targetDescription : "-";
            String reason = "<not_provided>";
            String idempotencyKey = "denied:" + auditId; // unique sentinel
            String detail = "PERMISSION_NOT_GRANTED endpoint=" + endpoint + " method=" + method;

            AdminActionJpaEntity entity = AdminActionJpaEntity.create(
                    auditId,
                    actionCode,
                    operatorId != null ? operatorId : "unknown",
                    "UNKNOWN",
                    (Long) null, // operator_id BIGINT FK — resolution deferred to TASK-BE-028b2
                    permissionUsed != null ? permissionUsed : Permission.MISSING,
                    targetType,
                    targetId,
                    reason,
                    null,
                    idempotencyKey,
                    Outcome.DENIED.name(),
                    detail,
                    now,
                    now);
            repository.save(entity);
        } catch (RuntimeException ex) {
            log.error("Failed to write DENIED admin_actions row (fail-closed): operatorId={} permission={}",
                    operatorId, permissionUsed, ex);
            throw new AuditFailureException("Failed to record DENIED admin action audit", ex);
        }
    }

    private static String permissionForActionCode(ActionCode code) {
        if (code == null) return Permission.MISSING;
        return switch (code) {
            case ACCOUNT_LOCK -> Permission.ACCOUNT_LOCK;
            case ACCOUNT_UNLOCK -> Permission.ACCOUNT_UNLOCK;
            case SESSION_REVOKE -> Permission.ACCOUNT_FORCE_LOGOUT;
            case AUDIT_QUERY -> Permission.AUDIT_READ;
        };
    }

    private static String actionCodeForPermission(String permissionKey) {
        if (permissionKey == null) return "UNKNOWN";
        return switch (permissionKey) {
            case Permission.ACCOUNT_LOCK -> ActionCode.ACCOUNT_LOCK.name();
            case Permission.ACCOUNT_UNLOCK -> ActionCode.ACCOUNT_UNLOCK.name();
            case Permission.ACCOUNT_FORCE_LOGOUT -> ActionCode.SESSION_REVOKE.name();
            case Permission.AUDIT_READ, Permission.SECURITY_EVENT_READ -> ActionCode.AUDIT_QUERY.name();
            default -> {
                if (permissionKey.contains("+")) yield ActionCode.AUDIT_QUERY.name();
                yield "UNKNOWN";
            }
        };
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
