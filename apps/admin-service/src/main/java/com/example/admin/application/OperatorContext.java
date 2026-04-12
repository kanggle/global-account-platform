package com.example.admin.application;

import java.util.Set;

/**
 * Represents the currently authenticated operator for the in-flight request.
 * Populated by {@code OperatorAuthenticationFilter} from a verified operator JWT
 * (scope="admin") and consumed by commands.
 */
public record OperatorContext(
        String operatorId,
        Set<OperatorRole> roles
) {
    public boolean hasRole(OperatorRole required) {
        if (roles.contains(OperatorRole.SUPER_ADMIN)) {
            return true;
        }
        return roles.contains(required);
    }
}
