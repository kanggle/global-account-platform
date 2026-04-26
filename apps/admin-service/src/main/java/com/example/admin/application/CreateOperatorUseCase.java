package com.example.admin.application;

import com.example.admin.application.exception.OperatorEmailConflictException;
import com.example.admin.application.exception.RoleNotFoundException;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaRepository;
import com.gap.security.password.PasswordHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CreateOperatorUseCase {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AdminOperatorJpaRepository operatorRepository;
    private final AdminOperatorRoleJpaRepository operatorRoleRepository;
    private final AdminRoleJpaRepository roleRepository;
    private final AdminActionAuditor auditor;
    private final PasswordHasher passwordHasher;

    @Transactional
    public CreateOperatorResult createOperator(String email,
                                               String displayName,
                                               String password,
                                               List<String> roleNames,
                                               OperatorContext actor,
                                               String reason) {
        String normalizedEmail = email == null ? null : email.trim().toLowerCase();
        if (normalizedEmail != null && operatorRepository.existsByEmail(normalizedEmail)) {
            throw new OperatorEmailConflictException("Operator email already exists");
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
                false,
                entity.getCreatedAt(),
                auditId);
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
}
