package com.example.admin.application;

import com.example.admin.application.exception.OperatorEmailConflictException;
import com.example.admin.application.exception.RoleNotFoundException;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorRoleJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaEntity;
import com.example.admin.infrastructure.persistence.rbac.AdminRoleJpaRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateOperatorUseCaseTest {

    @Mock AdminOperatorJpaRepository operatorRepository;
    @Mock AdminOperatorRoleJpaRepository operatorRoleRepository;
    @Mock AdminRoleJpaRepository roleRepository;
    @Mock AdminActionAuditor auditor;
    @Mock PasswordHasher passwordHasher;

    @InjectMocks CreateOperatorUseCase useCase;

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

        CreateOperatorUseCase.CreateOperatorResult result = useCase.createOperator(
                "new@example.com", "New Op", "StrongPass1!",
                List.of("SUPER_ADMIN"), actor(), "provisioning");

        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.roles()).containsExactly("SUPER_ADMIN");
        assertThat(result.totpEnrolled()).isFalse();
        assertThat(result.auditId()).isEqualTo("audit-new");

        verify(passwordHasher, times(1)).hash("StrongPass1!");
        verify(operatorRoleRepository, times(1)).saveAll(anyList());

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

        CreateOperatorUseCase.CreateOperatorResult result = useCase.createOperator(
                "empty@example.com", "Empty", "StrongPass1!",
                List.of(), actor(), "reason");

        assertThat(result.roles()).isEmpty();
        verify(operatorRoleRepository, never()).saveAll(anyList());
    }
}
