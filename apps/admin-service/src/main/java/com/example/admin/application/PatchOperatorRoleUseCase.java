package com.example.admin.application;

import com.example.admin.application.exception.OperatorNotFoundException;
import com.example.admin.application.exception.RoleNotFoundException;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.CachingPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PatchOperatorRoleUseCase {

    private final AdminOperatorJpaRepository operatorRepository;
    private final AdminOperatorRoleJpaRepository operatorRoleRepository;
    private final AdminRoleJpaRepository roleRepository;
    private final AdminActionAuditor auditor;
    private final CachingPermissionEvaluator cachingPermissionEvaluator;

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

        safeInvalidatePermissionCache(operatorUuid);

        List<String> orderedRoles = new ArrayList<>(resolvedRoles.keySet());
        return new PatchRolesResult(operatorUuid, orderedRoles, auditId);
    }

    private Map<String, AdminRoleJpaEntity> resolveRoles(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return new LinkedHashMap<>();
        }
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

    private Long resolveActorInternalId(OperatorContext actor) {
        if (actor == null || actor.operatorId() == null) return null;
        Optional<AdminOperatorJpaEntity> found = operatorRepository.findByOperatorId(actor.operatorId());
        return found.map(AdminOperatorJpaEntity::getId).orElse(null);
    }

    private static String normalizeReason(String reason) {
        return (reason == null || reason.isBlank()) ? "<not_provided>" : reason;
    }

    private void safeInvalidatePermissionCache(String operatorUuid) {
        if (cachingPermissionEvaluator == null) return;
        try {
            cachingPermissionEvaluator.invalidate(operatorUuid);
        } catch (RuntimeException ex) {
            log.warn("Permission cache invalidate failed for operatorId={} cause={}",
                    operatorUuid, ex.getClass().getSimpleName());
        }
    }

    public record PatchRolesResult(
            String operatorId,
            List<String> roles,
            String auditId
    ) {}
}
