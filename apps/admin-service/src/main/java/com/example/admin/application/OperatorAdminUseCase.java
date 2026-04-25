package com.example.admin.application;

import com.example.admin.application.exception.CurrentPasswordMismatchException;
import com.example.admin.application.exception.OperatorEmailConflictException;
import com.example.admin.application.exception.OperatorNotFoundException;
import com.example.admin.application.exception.OperatorUnauthorizedException;
import com.example.admin.application.exception.PasswordPolicyViolationException;
import com.example.admin.application.exception.RoleNotFoundException;
import com.example.admin.application.exception.SelfSuspendForbiddenException;
import com.example.admin.application.exception.StateTransitionInvalidException;
import com.example.admin.application.port.AdminRefreshTokenPort;
import com.example.admin.infrastructure.persistence.AdminOperatorTotpJpaEntity;
import com.example.admin.infrastructure.persistence.AdminOperatorTotpJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.CachingPermissionEvaluator;
import com.gap.security.password.PasswordHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * TASK-BE-083 — orchestrates SUPER_ADMIN-only operator management
 * (self lookup, listing, create, role patch, status patch). Follows the
 * admin-service "thin layered" convention: authorization is enforced upstream
 * by {@link com.example.admin.presentation.aspect.RequiresPermissionAspect}
 * and this service owns validation + persistence + audit + cache invalidation.
 *
 * <p>All mutation paths invoke {@link AdminActionAuditor#record(AdminActionAuditor.AuditRecord)}
 * with a terminal outcome. A10 fail-closed: if audit write throws, the
 * surrounding transaction rolls back and no partial state is committed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorAdminUseCase {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUSPENDED = "SUSPENDED";

    private final AdminOperatorJpaRepository operatorRepository;
    private final AdminOperatorRoleJpaRepository operatorRoleRepository;
    private final AdminRoleJpaRepository roleRepository;
    private final AdminOperatorTotpJpaRepository totpRepository;
    private final AdminActionAuditor auditor;
    private final PasswordHasher passwordHasher;
    private final CachingPermissionEvaluator cachingPermissionEvaluator;
    private final AdminRefreshTokenPort refreshTokenPort;

    // ------------------------------------------------------------------ GET /me

    /**
     * Resolves the authenticated operator's profile for {@code GET /api/admin/me}.
     * When the operator row is missing (stale/revoked JWT), throws
     * {@link OperatorUnauthorizedException} so the exception handler returns
     * {@code 401 TOKEN_INVALID} per the admin-api contract (no 404 defined
     * for this endpoint).
     */
    @Transactional(readOnly = true)
    public OperatorSummary getCurrentOperator(String operatorUuid) {
        AdminOperatorJpaEntity entity = operatorRepository.findByOperatorId(operatorUuid)
                .orElseThrow(() -> new OperatorUnauthorizedException(
                        "Operator not found for operatorId=" + operatorUuid));
        return toSummary(entity, loadRoleNames(entity.getId()));
    }

    // ----------------------------------------------------------- GET /operators

    @Transactional(readOnly = true)
    public OperatorPage listOperators(String statusFilter, int page, int size) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        PageRequest pageable = PageRequest.of(page, size, sort);

        Page<AdminOperatorJpaEntity> rows;
        if (statusFilter == null || statusFilter.isBlank()) {
            rows = operatorRepository.findAll(pageable);
        } else {
            rows = operatorRepository.findByStatus(statusFilter, pageable);
        }

        List<AdminOperatorJpaEntity> content = rows.getContent();
        Map<Long, List<String>> rolesByOperator = bulkLoadRoles(content);
        Set<Long> enrolledIds = bulkLoadEnrolledTotpIds(content);

        List<OperatorSummary> summaries = new ArrayList<>(content.size());
        for (AdminOperatorJpaEntity entity : content) {
            List<String> roles = rolesByOperator.getOrDefault(entity.getId(), List.of());
            summaries.add(new OperatorSummary(
                    entity.getOperatorId(),
                    entity.getEmail(),
                    entity.getDisplayName(),
                    entity.getStatus(),
                    roles,
                    entity.getTotpEnrolledAt() != null || enrolledIds.contains(entity.getId()),
                    entity.getLastLoginAt(),
                    entity.getCreatedAt()));
        }
        return new OperatorPage(summaries, rows.getTotalElements(), rows.getNumber(),
                rows.getSize(), rows.getTotalPages());
    }

    // ----------------------------------------------------------- POST /operators

    /**
     * Creates a new operator + initial role bindings in a single transaction,
     * then writes the {@code OPERATOR_CREATE} audit row. Duplicate email is
     * reported either through the pre-INSERT existence check or the unique
     * constraint violation (handles the race between two concurrent creates).
     */
    @Transactional
    public CreateOperatorResult createOperator(String email,
                                                String displayName,
                                                String password,
                                                List<String> roleNames,
                                                OperatorContext actor,
                                                String reason) {
        String normalizedEmail = email == null ? null : email.trim().toLowerCase();
        if (normalizedEmail != null && operatorRepository.existsByEmail(normalizedEmail)) {
            throw new OperatorEmailConflictException(
                    "Operator email already exists");
        }

        Map<String, AdminRoleJpaEntity> resolvedRoles = resolveRoles(roleNames);

        Instant now = Instant.now();
        String operatorUuid = AdminOperatorJpaEntity.newOperatorId();
        String passwordHash = passwordHasher.hash(password);

        AdminOperatorJpaEntity entity = AdminOperatorJpaEntity.create(
                operatorUuid, normalizedEmail, passwordHash, displayName, STATUS_ACTIVE, now);

        try {
            entity = operatorRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // Race: another transaction inserted the same email between our
            // existence check and this flush. Surface as the same 409 code.
            throw new OperatorEmailConflictException("Operator email already exists");
        }

        Long actorInternalId = resolveActorInternalId(actor);
        List<AdminOperatorRoleJpaEntity> bindings = new ArrayList<>(resolvedRoles.size());
        for (AdminRoleJpaEntity role : resolvedRoles.values()) {
            bindings.add(AdminOperatorRoleJpaEntity.create(entity.getId(), role.getId(), now, actorInternalId));
        }
        if (!bindings.isEmpty()) {
            operatorRoleRepository.saveAll(bindings);
        }

        String auditId = auditor.newAuditId();
        auditor.record(new AdminActionAuditor.AuditRecord(
                auditId,
                ActionCode.OPERATOR_CREATE,
                actor,
                "OPERATOR",
                operatorUuid,
                normalizeReason(reason),
                null,
                "create:" + auditId,
                Outcome.SUCCESS,
                null,
                now,
                Instant.now()));

        List<String> roleNamesOut = new ArrayList<>(resolvedRoles.keySet());
        return new CreateOperatorResult(
                operatorUuid,
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getStatus(),
                roleNamesOut,
                false, // newly created operators are always 2FA-unenrolled (spec)
                entity.getCreatedAt(),
                auditId);
    }

    // --------------------------------------- PATCH /operators/{id}/roles

    @Transactional
    public PatchRolesResult patchRoles(String operatorUuid,
                                        List<String> roleNames,
                                        OperatorContext actor,
                                        String reason) {
        AdminOperatorJpaEntity entity = operatorRepository.findByOperatorId(operatorUuid)
                .orElseThrow(() -> new OperatorNotFoundException(
                        "Operator not found for operatorId=" + operatorUuid));

        Map<String, AdminRoleJpaEntity> resolvedRoles = resolveRoles(roleNames);

        Instant now = Instant.now();
        Long actorInternalId = resolveActorInternalId(actor);

        // Full replacement: delete then insert. Safe because role bindings are
        // idempotent value objects (the composite PK carries the entire row's
        // semantic identity); no orphaned audit state to preserve.
        operatorRoleRepository.deleteByOperatorId(entity.getId());

        List<AdminOperatorRoleJpaEntity> bindings = new ArrayList<>(resolvedRoles.size());
        for (AdminRoleJpaEntity role : resolvedRoles.values()) {
            bindings.add(AdminOperatorRoleJpaEntity.create(entity.getId(), role.getId(), now, actorInternalId));
        }
        if (!bindings.isEmpty()) {
            operatorRoleRepository.saveAll(bindings);
        }

        String auditId = auditor.newAuditId();
        auditor.record(new AdminActionAuditor.AuditRecord(
                auditId,
                ActionCode.OPERATOR_ROLE_CHANGE,
                actor,
                "OPERATOR",
                operatorUuid,
                normalizeReason(reason),
                null,
                "roles:" + auditId,
                Outcome.SUCCESS,
                null,
                now,
                Instant.now()));

        // Invalidate the Redis permission cache for the patched operator. Spec
        // allows best-effort: Redis outage logs a warning and the 10s TTL
        // bounds staleness.
        safeInvalidatePermissionCache(operatorUuid);

        List<String> orderedRoles = new ArrayList<>(resolvedRoles.keySet());
        return new PatchRolesResult(operatorUuid, orderedRoles, auditId);
    }

    // -------------------------------------- PATCH /operators/{id}/status

    @Transactional
    public PatchStatusResult patchStatus(String operatorUuid,
                                          String newStatus,
                                          OperatorContext actor,
                                          String reason) {
        if (!STATUS_ACTIVE.equals(newStatus) && !STATUS_SUSPENDED.equals(newStatus)) {
            // Defensive: DTO validation should have caught this, but callers
            // bypassing validation (e.g. direct use-case unit tests) get the
            // same guarantee.
            throw new StateTransitionInvalidException(
                    "Unsupported operator status: " + newStatus);
        }
        if (STATUS_SUSPENDED.equals(newStatus)
                && Objects.equals(actor == null ? null : actor.operatorId(), operatorUuid)) {
            throw new SelfSuspendForbiddenException(
                    "Operators cannot suspend their own account");
        }

        AdminOperatorJpaEntity entity = operatorRepository.findByOperatorId(operatorUuid)
                .orElseThrow(() -> new OperatorNotFoundException(
                        "Operator not found for operatorId=" + operatorUuid));

        String previousStatus = entity.getStatus();
        if (Objects.equals(previousStatus, newStatus)) {
            throw new StateTransitionInvalidException(
                    "Operator status is already " + newStatus);
        }

        Instant now = Instant.now();
        entity.changeStatus(newStatus, now);
        operatorRepository.save(entity);

        if (STATUS_SUSPENDED.equals(newStatus)) {
            int revoked = refreshTokenPort.revokeAllForOperator(
                    entity.getId(), now, AdminRefreshTokenPort.REASON_FORCE_LOGOUT);
            log.info("Suspended operator {} — revoked {} refresh token(s)", operatorUuid, revoked);
        }

        String auditId = auditor.newAuditId();
        auditor.record(new AdminActionAuditor.AuditRecord(
                auditId,
                ActionCode.OPERATOR_STATUS_CHANGE,
                actor,
                "OPERATOR",
                operatorUuid,
                normalizeReason(reason),
                null,
                "status:" + auditId,
                Outcome.SUCCESS,
                "status:" + previousStatus + "->" + newStatus,
                now,
                Instant.now()));

        return new PatchStatusResult(operatorUuid, previousStatus, newStatus, auditId);
    }

    // --------------------------------- PATCH /operators/me/password

    /**
     * Changes the authenticated operator's own password.
     * Validates current password against the stored hash and enforces the
     * platform password policy before persisting the new hash.
     */
    @Transactional
    public void changeMyPassword(String operatorUuid, String currentPassword, String newPassword) {
        AdminOperatorJpaEntity entity = operatorRepository.findByOperatorId(operatorUuid)
                .orElseThrow(() -> new OperatorUnauthorizedException(
                        "Operator not found for operatorId=" + operatorUuid));

        if (!passwordHasher.verify(currentPassword, entity.getPasswordHash())) {
            throw new CurrentPasswordMismatchException();
        }
        validatePasswordPolicy(newPassword);

        String newHash = passwordHasher.hash(newPassword);
        entity.changePasswordHash(newHash, Instant.now());
        operatorRepository.save(entity);
    }

    private static void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new PasswordPolicyViolationException(
                    "Password must be 8–128 characters long");
        }
        int categories = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) categories++;
        if (password.chars().anyMatch(Character::isLowerCase)) categories++;
        if (password.chars().anyMatch(Character::isDigit)) categories++;
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) categories++;
        if (categories < 3) {
            throw new PasswordPolicyViolationException(
                    "Password must contain at least 3 of: uppercase, lowercase, digit, special character");
        }
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, AdminRoleJpaEntity> resolveRoles(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return new LinkedHashMap<>();
        }
        // Deduplicate while preserving caller order so the response echoes the
        // request ordering (minus duplicates).
        LinkedHashMap<String, AdminRoleJpaEntity> out = new LinkedHashMap<>();
        List<AdminRoleJpaEntity> found = roleRepository.findByNameIn(roleNames);
        Map<String, AdminRoleJpaEntity> byName = new LinkedHashMap<>();
        for (AdminRoleJpaEntity r : found) {
            byName.put(r.getName(), r);
        }
        for (String name : roleNames) {
            if (name == null || name.isBlank()) continue;
            AdminRoleJpaEntity role = byName.get(name);
            if (role == null) {
                throw new RoleNotFoundException("Unknown role name: " + name);
            }
            out.putIfAbsent(name, role);
        }
        return out;
    }

    private List<String> loadRoleNames(Long operatorPk) {
        List<AdminOperatorRoleJpaEntity> bindings = operatorRoleRepository.findByOperatorId(operatorPk);
        if (bindings.isEmpty()) return List.of();
        List<Long> roleIds = new ArrayList<>(bindings.size());
        for (AdminOperatorRoleJpaEntity b : bindings) roleIds.add(b.getRoleId());
        List<AdminRoleJpaEntity> roles = roleRepository.findAllById(roleIds);
        List<String> names = new ArrayList<>(roles.size());
        for (AdminRoleJpaEntity r : roles) names.add(r.getName());
        Collections.sort(names);
        return names;
    }

    private Map<Long, List<String>> bulkLoadRoles(List<AdminOperatorJpaEntity> operators) {
        if (operators.isEmpty()) return Map.of();
        List<Long> ids = new ArrayList<>(operators.size());
        for (AdminOperatorJpaEntity o : operators) ids.add(o.getId());
        List<AdminOperatorRoleJpaEntity> bindings = operatorRoleRepository.findByOperatorIdIn(ids);
        if (bindings.isEmpty()) return Map.of();

        List<Long> roleIds = new ArrayList<>(bindings.size());
        for (AdminOperatorRoleJpaEntity b : bindings) roleIds.add(b.getRoleId());
        Map<Long, String> roleNameById = new LinkedHashMap<>();
        for (AdminRoleJpaEntity r : roleRepository.findAllById(roleIds)) {
            roleNameById.put(r.getId(), r.getName());
        }

        Map<Long, List<String>> byOperator = new LinkedHashMap<>();
        for (AdminOperatorRoleJpaEntity b : bindings) {
            String roleName = roleNameById.get(b.getRoleId());
            if (roleName == null) continue;
            byOperator.computeIfAbsent(b.getOperatorId(), k -> new ArrayList<>()).add(roleName);
        }
        for (List<String> names : byOperator.values()) Collections.sort(names);
        return byOperator;
    }

    /**
     * TASK-BE-083: for each operator in the current page, detect whether a
     * corresponding {@code admin_operator_totp} row exists. The {@code admin_operators.totp_enrolled_at}
     * legacy column may be NULL even when enrollment completed (TASK-BE-029
     * stores state in the side table), so we cross-check.
     *
     * <p>TASK-BE-084: replaced the per-operator {@code findById} loop with a
     * single {@code findByOperatorIdIn} call to eliminate the N+1 query.
     */
    private Set<Long> bulkLoadEnrolledTotpIds(Collection<AdminOperatorJpaEntity> operators) {
        if (operators.isEmpty()) return Set.of();
        List<Long> operatorInternalIds = new ArrayList<>(operators.size());
        for (AdminOperatorJpaEntity op : operators) operatorInternalIds.add(op.getId());

        Set<Long> enrolled = new java.util.HashSet<>();
        for (AdminOperatorTotpJpaEntity row : totpRepository.findByOperatorIdIn(operatorInternalIds)) {
            if (isTotpEnrolled(row)) enrolled.add(row.getOperatorId());
        }
        return enrolled;
    }

    private static boolean isTotpEnrolled(AdminOperatorTotpJpaEntity row) {
        return row != null && row.getEnrolledAt() != null;
    }

    private Long resolveActorInternalId(OperatorContext actor) {
        if (actor == null || actor.operatorId() == null) return null;
        return operatorRepository.findByOperatorId(actor.operatorId())
                .map(AdminOperatorJpaEntity::getId)
                .orElse(null);
    }

    private OperatorSummary toSummary(AdminOperatorJpaEntity entity, List<String> roles) {
        boolean totpEnrolled = entity.getTotpEnrolledAt() != null
                || totpRepository.findById(entity.getId())
                        .map(OperatorAdminUseCase::isTotpEnrolled).orElse(false);
        return new OperatorSummary(
                entity.getOperatorId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getStatus(),
                roles,
                totpEnrolled,
                entity.getLastLoginAt(),
                entity.getCreatedAt());
    }

    private static String normalizeReason(String reason) {
        return (reason == null || reason.isBlank()) ? "<not_provided>" : reason;
    }

    private void safeInvalidatePermissionCache(String operatorUuid) {
        if (cachingPermissionEvaluator == null) return;
        try {
            cachingPermissionEvaluator.invalidate(operatorUuid);
        } catch (RuntimeException ex) {
            // 10s TTL ensures eventual consistency; spec explicitly allows
            // logging and proceeding.
            log.warn("Permission cache invalidate failed for operatorId={} cause={}",
                    operatorUuid, ex.getClass().getSimpleName());
        }
    }

    // --------------------------------------------------------- result records

    public record OperatorSummary(
            String operatorId,
            String email,
            String displayName,
            String status,
            List<String> roles,
            boolean totpEnrolled,
            Instant lastLoginAt,
            Instant createdAt
    ) {}

    public record OperatorPage(
            List<OperatorSummary> content,
            long totalElements,
            int page,
            int size,
            int totalPages
    ) {}

    public record CreateOperatorResult(
            String operatorId,
            String email,
            String displayName,
            String status,
            List<String> roles,
            boolean totpEnrolled,
            Instant createdAt,
            String auditId
    ) {}

    public record PatchRolesResult(
            String operatorId,
            List<String> roles,
            String auditId
    ) {}

    public record PatchStatusResult(
            String operatorId,
            String previousStatus,
            String currentStatus,
            String auditId
    ) {}
}
