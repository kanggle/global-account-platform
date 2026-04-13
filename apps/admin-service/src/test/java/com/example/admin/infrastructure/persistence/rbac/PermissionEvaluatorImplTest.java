package com.example.admin.infrastructure.persistence.rbac;

import com.example.admin.domain.rbac.AdminOperator;
import com.example.admin.domain.rbac.Permission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class PermissionEvaluatorImplTest {

    @Mock AdminOperatorJpaRepository operators;
    @Mock AdminOperatorRoleJpaRepository operatorRoles;
    @Mock AdminRolePermissionJpaRepository rolePermissions;

    @InjectMocks PermissionEvaluatorImpl evaluator;

    private AdminOperatorJpaEntity activeOperator(String id) {
        AdminOperatorJpaEntity e = new AdminOperatorJpaEntity() {};
        try {
            var f1 = AdminOperatorJpaEntity.class.getDeclaredField("id"); f1.setAccessible(true); f1.set(e, id);
            var f2 = AdminOperatorJpaEntity.class.getDeclaredField("status"); f2.setAccessible(true);
            f2.set(e, AdminOperator.Status.ACTIVE.name());
            var f3 = AdminOperatorJpaEntity.class.getDeclaredField("email"); f3.setAccessible(true); f3.set(e, "x@example.com");
            var f4 = AdminOperatorJpaEntity.class.getDeclaredField("passwordHash"); f4.setAccessible(true); f4.set(e, "h");
            var f5 = AdminOperatorJpaEntity.class.getDeclaredField("displayName"); f5.setAccessible(true); f5.set(e, "n");
            var f6 = AdminOperatorJpaEntity.class.getDeclaredField("createdAt"); f6.setAccessible(true); f6.set(e, Instant.now());
            var f7 = AdminOperatorJpaEntity.class.getDeclaredField("updatedAt"); f7.setAccessible(true); f7.set(e, Instant.now());
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        return e;
    }

    private AdminOperatorJpaEntity inactiveOperator(String id) {
        AdminOperatorJpaEntity e = activeOperator(id);
        try {
            var f = AdminOperatorJpaEntity.class.getDeclaredField("status"); f.setAccessible(true);
            f.set(e, AdminOperator.Status.DISABLED.name());
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        return e;
    }

    private AdminOperatorRoleJpaEntity roleBinding(String op, Long role) {
        return AdminOperatorRoleJpaEntity.create(op, role, Instant.now(), null);
    }

    @Test
    void hasPermission_returns_true_for_union_across_roles() {
        when(operators.findById("op-1")).thenReturn(Optional.of(activeOperator("op-1")));
        when(operatorRoles.findByOperatorId("op-1")).thenReturn(
                List.of(roleBinding("op-1", 1L), roleBinding("op-1", 2L)));
        when(rolePermissions.findPermissionKeysByRoleIds(anyCollection()))
                .thenReturn(List.of(Permission.AUDIT_READ, Permission.ACCOUNT_LOCK));

        assertThat(evaluator.hasPermission("op-1", Permission.AUDIT_READ)).isTrue();
    }

    @Test
    void hasPermission_returns_false_for_missing_operator() {
        when(operators.findById("ghost")).thenReturn(Optional.empty());

        assertThat(evaluator.hasPermission("ghost", Permission.AUDIT_READ)).isFalse();
    }

    @Test
    void hasPermission_returns_false_for_inactive_operator() {
        when(operators.findById("op-2")).thenReturn(Optional.of(inactiveOperator("op-2")));

        assertThat(evaluator.hasPermission("op-2", Permission.AUDIT_READ)).isFalse();
    }

    @Test
    void hasAllPermissions_requires_full_containment() {
        when(operators.findById("op-3")).thenReturn(Optional.of(activeOperator("op-3")));
        when(operatorRoles.findByOperatorId("op-3")).thenReturn(List.of(roleBinding("op-3", 1L)));
        when(rolePermissions.findPermissionKeysByRoleIds(anyCollection()))
                .thenReturn(List.of(Permission.AUDIT_READ));

        assertThat(evaluator.hasAllPermissions("op-3",
                List.of(Permission.AUDIT_READ, Permission.SECURITY_EVENT_READ))).isFalse();
        assertThat(evaluator.hasAllPermissions("op-3", List.of(Permission.AUDIT_READ))).isTrue();
    }

    @Test
    void hasPermission_null_inputs_return_false() {
        lenient().when(operators.findById("x")).thenReturn(Optional.empty());
        assertThat(evaluator.hasPermission(null, Permission.AUDIT_READ)).isFalse();
        assertThat(evaluator.hasPermission("x", null)).isFalse();
    }
}
