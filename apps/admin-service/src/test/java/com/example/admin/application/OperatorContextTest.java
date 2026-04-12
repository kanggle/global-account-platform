package com.example.admin.application;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorContextTest {

    @Test
    void super_admin_has_all_roles() {
        OperatorContext ctx = new OperatorContext("op-1", Set.of(OperatorRole.SUPER_ADMIN));
        assertThat(ctx.hasRole(OperatorRole.ACCOUNT_ADMIN)).isTrue();
        assertThat(ctx.hasRole(OperatorRole.AUDITOR)).isTrue();
        assertThat(ctx.hasRole(OperatorRole.SUPER_ADMIN)).isTrue();
    }

    @Test
    void auditor_does_not_have_account_admin() {
        OperatorContext ctx = new OperatorContext("op-1", Set.of(OperatorRole.AUDITOR));
        assertThat(ctx.hasRole(OperatorRole.ACCOUNT_ADMIN)).isFalse();
        assertThat(ctx.hasRole(OperatorRole.AUDITOR)).isTrue();
    }
}
