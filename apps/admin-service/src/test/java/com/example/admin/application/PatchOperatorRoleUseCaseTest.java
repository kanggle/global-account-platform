package com.example.admin.application;

import com.example.admin.application.exception.OperatorNotFoundException;
import com.example.admin.application.exception.RoleNotFoundException;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.CachingPermissionEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatchOperatorRoleUseCaseTest {

    @Mock AdminOperatorJpaRepository operatorRepository;
    @Mock AdminOperatorRoleJpaRepository operatorRoleRepository;
    @Mock AdminRoleJpaRepository roleRepository;
    @Mock AdminActionAuditor auditor;
    @Mock CachingPermissionEvaluator cachingPermissionEvaluator;

    @InjectMocks PatchOperatorRoleUseCase useCase;

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
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

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

        PatchOperatorRoleUseCase.PatchRolesResult result = useCase.patchRoles(
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

        PatchOperatorRoleUseCase.PatchRolesResult result = useCase.patchRoles(
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
}
