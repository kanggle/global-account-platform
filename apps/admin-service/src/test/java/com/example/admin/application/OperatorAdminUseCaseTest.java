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
import com.example.admin.infrastructure.persistence.AdminOperatorTotpJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.CachingPermissionEvaluator;
import com.gap.security.password.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperatorAdminUseCaseTest {

    @Mock
    AdminOperatorJpaRepository operatorRepository;
    @Mock
    AdminOperatorRoleJpaRepository operatorRoleRepository;
    @Mock
    AdminRoleJpaRepository roleRepository;
    @Mock
    AdminOperatorTotpJpaRepository totpRepository;
    @Mock
    AdminActionAuditor auditor;
    @Mock
    PasswordHasher passwordHasher;
    @Mock
    CachingPermissionEvaluator cachingPermissionEvaluator;
    @Mock
    AdminRefreshTokenPort refreshTokenPort;

    @InjectMocks
    OperatorAdminUseCase useCase;

    private OperatorContext actor() {
        return new OperatorContext("actor-uuid", "jti-1");
    }

    private AdminRoleJpaEntity role(Long id, String name) {
        try {
            var ctor = AdminRoleJpaEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            AdminRoleJpaEntity r = ctor.newInstance();
            setField(r, "id", id);
            setField(r, "name", name);
            setField(r, "description", name);
            return r;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AdminOperatorJpaEntity operator(Long id, String uuid, String email, String status) {
        AdminOperatorJpaEntity e = AdminOperatorJpaEntity.create(
                uuid, email, "hash", "Display", status, Instant.parse("2026-01-01T00:00:00Z"));
        setField(e, "id", id);
        return e;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // -------------------------------------------- getCurrentOperator

    @Test
    void getCurrentOperator_returns_summary_with_roles() {
        AdminOperatorJpaEntity op = operator(10L, "op-uuid", "op@example.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("op-uuid")).thenReturn(Optional.of(op));
        when(operatorRoleRepository.findByOperatorId(10L)).thenReturn(List.of(
                AdminOperatorRoleJpaEntity.create(10L, 1L, Instant.now(), null)));
        when(roleRepository.findAllById(anyCollection())).thenReturn(List.of(role(1L, "SUPER_ADMIN")));
        when(totpRepository.findById(10L)).thenReturn(Optional.empty());

        OperatorAdminUseCase.OperatorSummary result = useCase.getCurrentOperator("op-uuid");

        assertThat(result.operatorId()).isEqualTo("op-uuid");
        assertThat(result.email()).isEqualTo("op@example.com");
        assertThat(result.roles()).containsExactly("SUPER_ADMIN");
        assertThat(result.totpEnrolled()).isFalse();
    }

    @Test
    void getCurrentOperator_missing_operator_throws_unauthorized() {
        // TASK-BE-084: missing row on GET /me must surface as 401 TOKEN_INVALID
        // (the contract does not define 404 for this endpoint).
        when(operatorRepository.findByOperatorId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getCurrentOperator("missing"))
                .isInstanceOf(OperatorUnauthorizedException.class);
    }

    // ---------------------------------------------------- createOperator

    @Test
    void createOperator_success_persists_hash_and_audits() {
        when(operatorRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(roleRepository.findByNameIn(List.of("SUPER_ADMIN")))
                .thenReturn(List.of(role(1L, "SUPER_ADMIN")));
        when(passwordHasher.hash("StrongPass1!")).thenReturn("hash-value");
        when(operatorRepository.saveAndFlush(any(AdminOperatorJpaEntity.class)))
                .thenAnswer(inv -> {
                    AdminOperatorJpaEntity e = inv.getArgument(0);
                    setField(e, "id", 42L);
                    return e;
                });
        when(operatorRepository.findByOperatorId("actor-uuid"))
                .thenReturn(Optional.of(operator(99L, "actor-uuid", "a@ex.com", "ACTIVE")));
        when(auditor.newAuditId()).thenReturn("audit-new");

        OperatorAdminUseCase.CreateOperatorResult result = useCase.createOperator(
                "new@example.com", "New Op", "StrongPass1!",
                List.of("SUPER_ADMIN"), actor(), "provisioning");

        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.roles()).containsExactly("SUPER_ADMIN");
        assertThat(result.totpEnrolled()).isFalse();
        assertThat(result.auditId()).isEqualTo("audit-new");

        // Password plaintext must be hashed via PasswordHasher (never stored raw).
        verify(passwordHasher, times(1)).hash("StrongPass1!");
        // Role bindings saved once.
        verify(operatorRoleRepository, times(1)).saveAll(anyList());

        // Audit captures OPERATOR_CREATE with operator UUID as target_id.
        ArgumentCaptor<AdminActionAuditor.AuditRecord> captor =
                ArgumentCaptor.forClass(AdminActionAuditor.AuditRecord.class);
        verify(auditor).record(captor.capture());
        assertThat(captor.getValue().actionCode()).isEqualTo(ActionCode.OPERATOR_CREATE);
        assertThat(captor.getValue().targetType()).isEqualTo("OPERATOR");
        assertThat(captor.getValue().outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(captor.getValue().reason()).isEqualTo("provisioning");
    }

    @Test
    void createOperator_duplicate_email_throws_conflict_before_persist() {
        when(operatorRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> useCase.createOperator(
                "dup@example.com", "Dup", "StrongPass1!", List.of(), actor(), "reason"))
                .isInstanceOf(OperatorEmailConflictException.class);

        verify(operatorRepository, never()).saveAndFlush(any());
        verify(auditor, never()).record(any());
    }

    @Test
    void createOperator_unknown_role_throws_role_not_found() {
        when(operatorRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByNameIn(List.of("DOES_NOT_EXIST"))).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.createOperator(
                "ok@example.com", "Op", "StrongPass1!",
                List.of("DOES_NOT_EXIST"), actor(), "reason"))
                .isInstanceOf(RoleNotFoundException.class);

        verify(operatorRepository, never()).saveAndFlush(any());
    }

    @Test
    void createOperator_empty_roles_is_allowed() {
        when(operatorRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordHasher.hash(anyString())).thenReturn("h");
        when(operatorRepository.saveAndFlush(any(AdminOperatorJpaEntity.class)))
                .thenAnswer(inv -> {
                    AdminOperatorJpaEntity e = inv.getArgument(0);
                    setField(e, "id", 55L);
                    return e;
                });
        when(operatorRepository.findByOperatorId("actor-uuid")).thenReturn(Optional.empty());
        when(auditor.newAuditId()).thenReturn("audit-2");

        OperatorAdminUseCase.CreateOperatorResult result = useCase.createOperator(
                "empty@example.com", "Empty", "StrongPass1!",
                List.of(), actor(), "reason");

        assertThat(result.roles()).isEmpty();
        // No role binding save when roles empty.
        verify(operatorRoleRepository, never()).saveAll(anyList());
    }

    // ------------------------------------------------------ patchRoles

    @Test
    void patchRoles_replaces_all_and_invalidates_cache() {
        AdminOperatorJpaEntity target = operator(77L, "target-uuid", "t@ex.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("target-uuid")).thenReturn(Optional.of(target));
        when(roleRepository.findByNameIn(List.of("SUPPORT_READONLY", "SECURITY_ANALYST")))
                .thenReturn(List.of(
                        role(2L, "SUPPORT_READONLY"),
                        role(4L, "SECURITY_ANALYST")));
        when(operatorRepository.findByOperatorId("actor-uuid"))
                .thenReturn(Optional.of(operator(99L, "actor-uuid", "a@ex.com", "ACTIVE")));
        when(auditor.newAuditId()).thenReturn("audit-patch");

        OperatorAdminUseCase.PatchRolesResult result = useCase.patchRoles(
                "target-uuid",
                List.of("SUPPORT_READONLY", "SECURITY_ANALYST"),
                actor(),
                "quarterly rotation");

        assertThat(result.roles()).containsExactly("SUPPORT_READONLY", "SECURITY_ANALYST");
        verify(operatorRoleRepository).deleteByOperatorId(77L);
        verify(operatorRoleRepository).saveAll(anyList());
        verify(cachingPermissionEvaluator).invalidate("target-uuid");
    }

    @Test
    void patchRoles_empty_array_allowed_still_invalidates_cache() {
        AdminOperatorJpaEntity target = operator(77L, "target-uuid", "t@ex.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("target-uuid")).thenReturn(Optional.of(target));
        when(operatorRepository.findByOperatorId("actor-uuid")).thenReturn(Optional.empty());
        when(auditor.newAuditId()).thenReturn("audit-empty");

        OperatorAdminUseCase.PatchRolesResult result = useCase.patchRoles(
                "target-uuid", List.of(), actor(), "demotion");

        assertThat(result.roles()).isEmpty();
        verify(operatorRoleRepository).deleteByOperatorId(77L);
        verify(operatorRoleRepository, never()).saveAll(anyList());
        verify(cachingPermissionEvaluator).invalidate("target-uuid");
    }

    @Test
    void patchRoles_unknown_role_throws_and_skips_delete() {
        AdminOperatorJpaEntity target = operator(77L, "target-uuid", "t@ex.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("target-uuid")).thenReturn(Optional.of(target));
        when(roleRepository.findByNameIn(List.of("GHOST"))).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.patchRoles(
                "target-uuid", List.of("GHOST"), actor(), "reason"))
                .isInstanceOf(RoleNotFoundException.class);

        verify(operatorRoleRepository, never()).deleteByOperatorId(anyLong());
        verify(cachingPermissionEvaluator, never()).invalidate(anyString());
    }

    @Test
    void patchRoles_missing_operator_throws_not_found() {
        when(operatorRepository.findByOperatorId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.patchRoles(
                "ghost", List.of(), actor(), "reason"))
                .isInstanceOf(OperatorNotFoundException.class);
    }

    // ----------------------------------------------------- patchStatus

    @Test
    void patchStatus_active_to_suspended_revokes_refresh_tokens() {
        AdminOperatorJpaEntity target = operator(77L, "target-uuid", "t@ex.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("target-uuid")).thenReturn(Optional.of(target));
        when(auditor.newAuditId()).thenReturn("audit-sus");

        OperatorAdminUseCase.PatchStatusResult result = useCase.patchStatus(
                "target-uuid", "SUSPENDED", actor(), "violation");

        assertThat(result.previousStatus()).isEqualTo("ACTIVE");
        assertThat(result.currentStatus()).isEqualTo("SUSPENDED");
        assertThat(target.getStatus()).isEqualTo("SUSPENDED");
        verify(refreshTokenPort).revokeAllForOperator(eq(77L), any(Instant.class), anyString());
    }

    @Test
    void patchStatus_suspended_to_active_does_not_touch_tokens() {
        AdminOperatorJpaEntity target = operator(77L, "target-uuid", "t@ex.com", "SUSPENDED");
        when(operatorRepository.findByOperatorId("target-uuid")).thenReturn(Optional.of(target));
        when(auditor.newAuditId()).thenReturn("audit-rest");

        OperatorAdminUseCase.PatchStatusResult result = useCase.patchStatus(
                "target-uuid", "ACTIVE", actor(), "cleared");

        assertThat(result.currentStatus()).isEqualTo("ACTIVE");
        verify(refreshTokenPort, never()).revokeAllForOperator(anyLong(), any(), anyString());
    }

    @Test
    void patchStatus_self_suspend_rejected() {
        OperatorContext self = new OperatorContext("self-uuid", "jti-x");

        assertThatThrownBy(() -> useCase.patchStatus(
                "self-uuid", "SUSPENDED", self, "reason"))
                .isInstanceOf(SelfSuspendForbiddenException.class);

        verify(operatorRepository, never()).save(any());
    }

    @Test
    void patchStatus_same_status_throws_state_transition_invalid() {
        AdminOperatorJpaEntity target = operator(77L, "target-uuid", "t@ex.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("target-uuid")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> useCase.patchStatus(
                "target-uuid", "ACTIVE", actor(), "reason"))
                .isInstanceOf(StateTransitionInvalidException.class);
    }

    @Test
    void patchStatus_missing_operator_throws_not_found() {
        when(operatorRepository.findByOperatorId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.patchStatus(
                "ghost", "SUSPENDED", actor(), "reason"))
                .isInstanceOf(OperatorNotFoundException.class);
    }

    // --------------------------------------------------- listOperators

    @Test
    void listOperators_returns_paginated_roles() {
        AdminOperatorJpaEntity op = operator(1L, "op-1-uuid", "one@ex.com", "ACTIVE");
        org.springframework.data.domain.Page<AdminOperatorJpaEntity> page =
                new org.springframework.data.domain.PageImpl<>(
                        List.of(op),
                        org.springframework.data.domain.PageRequest.of(0, 20),
                        1L);
        when(operatorRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);
        when(operatorRoleRepository.findByOperatorIdIn(anyCollection()))
                .thenReturn(List.of(AdminOperatorRoleJpaEntity.create(1L, 3L, Instant.now(), null)));
        when(roleRepository.findAllById(anyCollection()))
                .thenReturn(List.of(role(3L, "SUPPORT_LOCK")));
        // TASK-BE-084: bulkLoadEnrolledTotpIds now issues a single IN query.
        when(totpRepository.findByOperatorIdIn(anyCollection())).thenReturn(List.of());

        OperatorAdminUseCase.OperatorPage result = useCase.listOperators(null, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).roles()).containsExactly("SUPPORT_LOCK");
        assertThat(result.totalElements()).isEqualTo(1L);
    }

    @Test
    void listOperators_status_filter_routes_to_status_query() {
        org.springframework.data.domain.Page<AdminOperatorJpaEntity> empty =
                new org.springframework.data.domain.PageImpl<>(List.of());
        when(operatorRepository.findByStatus(eq("SUSPENDED"),
                any(org.springframework.data.domain.Pageable.class))).thenReturn(empty);

        OperatorAdminUseCase.OperatorPage result = useCase.listOperators("SUSPENDED", 0, 20);

        assertThat(result.content()).isEmpty();
        verify(operatorRepository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    // ----------------------------------------------- changeMyPassword (TASK-BE-086)

    @Test
    void changeMyPassword_valid_current_and_policy_compliant_new_saves_new_hash() {
        // Happy path: verify() returns true, new password satisfies ≥8 chars and
        // ≥3 of 4 categories. The entity's stored hash must be replaced via
        // changePasswordHash() and the row saved exactly once.
        AdminOperatorJpaEntity op = operator(10L, "op-uuid", "op@example.com", "ACTIVE");
        String originalHash = op.getPasswordHash();
        when(operatorRepository.findByOperatorId("op-uuid")).thenReturn(Optional.of(op));
        when(passwordHasher.verify("OldPass1!", originalHash)).thenReturn(true);
        when(passwordHasher.hash("NewPass2@")).thenReturn("new-hash-value");

        useCase.changeMyPassword("op-uuid", "OldPass1!", "NewPass2@");

        assertThat(op.getPasswordHash()).isEqualTo("new-hash-value");
        verify(passwordHasher, times(1)).verify("OldPass1!", originalHash);
        verify(passwordHasher, times(1)).hash("NewPass2@");
        verify(operatorRepository, times(1)).save(op);
    }

    @Test
    void changeMyPassword_current_password_mismatch_throws_and_skips_save() {
        // verify() returns false → CurrentPasswordMismatchException surfaces as
        // 400 CURRENT_PASSWORD_MISMATCH. No hash computation and no save.
        AdminOperatorJpaEntity op = operator(10L, "op-uuid", "op@example.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("op-uuid")).thenReturn(Optional.of(op));
        when(passwordHasher.verify("Wrong1!", op.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() ->
                useCase.changeMyPassword("op-uuid", "Wrong1!", "NewPass2@"))
                .isInstanceOf(CurrentPasswordMismatchException.class);

        verify(passwordHasher, never()).hash(anyString());
        verify(operatorRepository, never()).save(any(AdminOperatorJpaEntity.class));
    }

    @Test
    void changeMyPassword_policy_violation_too_short_throws_before_hashing() {
        // New password only 4 chars (< 8) → PASSWORD_POLICY_VIOLATION. The
        // production code validates policy AFTER verify(), so stub verify()=true
        // to isolate the policy check.
        AdminOperatorJpaEntity op = operator(10L, "op-uuid", "op@example.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("op-uuid")).thenReturn(Optional.of(op));
        when(passwordHasher.verify("OldPass1!", op.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() ->
                useCase.changeMyPassword("op-uuid", "OldPass1!", "Ab1!"))
                .isInstanceOf(PasswordPolicyViolationException.class);

        verify(passwordHasher, never()).hash(anyString());
        verify(operatorRepository, never()).save(any(AdminOperatorJpaEntity.class));
    }

    @Test
    void changeMyPassword_policy_violation_two_categories_only_throws() {
        // "alllower1" has length ≥ 8 but only 2 of 4 categories (lower + digit).
        // Policy requires ≥ 3 categories.
        AdminOperatorJpaEntity op = operator(10L, "op-uuid", "op@example.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("op-uuid")).thenReturn(Optional.of(op));
        when(passwordHasher.verify("OldPass1!", op.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() ->
                useCase.changeMyPassword("op-uuid", "OldPass1!", "alllower1"))
                .isInstanceOf(PasswordPolicyViolationException.class);

        verify(passwordHasher, never()).hash(anyString());
        verify(operatorRepository, never()).save(any(AdminOperatorJpaEntity.class));
    }

    @Test
    void changeMyPassword_policy_violation_exceeds_128_chars_throws() {
        // Edge case per task: password of 129 chars is rejected (upper bound).
        String tooLong = "A1!" + "a".repeat(126); // 3 + 126 = 129 chars, covers 3 categories
        AdminOperatorJpaEntity op = operator(10L, "op-uuid", "op@example.com", "ACTIVE");
        when(operatorRepository.findByOperatorId("op-uuid")).thenReturn(Optional.of(op));
        when(passwordHasher.verify("OldPass1!", op.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() ->
                useCase.changeMyPassword("op-uuid", "OldPass1!", tooLong))
                .isInstanceOf(PasswordPolicyViolationException.class);

        verify(passwordHasher, never()).hash(anyString());
        verify(operatorRepository, never()).save(any(AdminOperatorJpaEntity.class));
    }
}
