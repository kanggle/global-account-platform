package com.example.admin.presentation.aspect;

import com.example.admin.application.AdminActionAuditor;
import com.example.admin.application.OperatorContext;
import com.example.admin.application.exception.PermissionDeniedException;
import com.example.admin.domain.rbac.PermissionEvaluator;
import com.example.admin.infrastructure.security.OperatorContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates {@link RequiresPermission} declarations after Spring Security's
 * {@code @PreAuthorize} (which runs at the security interceptor phase). On
 * denial, writes a single admin_actions row with outcome=DENIED via
 * {@link AdminActionAuditor#recordDenied} and throws
 * {@link PermissionDeniedException} → 403 PERMISSION_DENIED.
 */
@Slf4j
@Aspect
@Component
@Order(100) // runs after @PreAuthorize (MethodSecurityInterceptor order ~0)
@RequiredArgsConstructor
public class RequiresPermissionAspect {

    private final PermissionEvaluator permissionEvaluator;
    private final AdminActionAuditor auditor;

    @Around("@annotation(requires)")
    public Object check(ProceedingJoinPoint pjp, RequiresPermission requires) throws Throwable {
        List<String> required = collectRequired(requires);
        OperatorContext op = OperatorContextHolder.require();
        String operatorId = op.operatorId();

        boolean granted;
        if (required.size() == 1) {
            granted = permissionEvaluator.hasPermission(operatorId, required.get(0));
        } else {
            granted = permissionEvaluator.hasAllPermissions(operatorId, required);
        }

        if (!granted) {
            String permissionUsed = joinKeys(required);
            HttpServletRequest request = currentRequest();
            String endpoint = request != null ? request.getRequestURI() : null;
            String method = request != null ? request.getMethod() : null;
            auditor.recordDenied(operatorId, permissionUsed, endpoint, method, null);
            throw new PermissionDeniedException(
                    "Operator lacks required permission: " + permissionUsed);
        }

        return pjp.proceed();
    }

    private static List<String> collectRequired(RequiresPermission a) {
        List<String> keys = new ArrayList<>();
        if (a.value() != null && !a.value().isBlank()) {
            keys.add(a.value());
        }
        for (String k : a.allOf()) {
            if (k != null && !k.isBlank() && !keys.contains(k)) {
                keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException(
                    "@RequiresPermission must declare at least one key (value or allOf)");
        }
        return keys;
    }

    private static String joinKeys(List<String> keys) {
        return keys.size() == 1 ? keys.get(0) : String.join("+", keys);
    }

    private static HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (IllegalStateException ex) {
            return null;
        }
    }
}
