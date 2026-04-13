package com.example.admin.presentation;

import com.example.admin.application.AuditQueryUseCase;
import com.example.admin.application.OperatorContext;
import com.example.admin.application.QueryAuditCommand;
import com.example.admin.application.exception.PermissionDeniedException;
import com.example.admin.domain.rbac.Permission;
import com.example.admin.domain.rbac.PermissionEvaluator;
import com.example.admin.infrastructure.security.OperatorContextHolder;
import com.example.admin.presentation.aspect.RequiresPermission;
import com.example.admin.presentation.dto.AuditQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryUseCase useCase;
    private final PermissionEvaluator permissionEvaluator;

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','ACCOUNT_ADMIN','SUPER_ADMIN')")
    @RequiresPermission(Permission.AUDIT_READ)
    public ResponseEntity<AuditQueryResponse> query(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator-Reason", required = false) String reason) {

        OperatorContext op = OperatorContextHolder.require();

        // TASK-BE-028a: cross-permission check for security-event sources.
        // When source requires security.event.read, both audit.read (already enforced
        // by @RequiresPermission above) AND security.event.read are required.
        if (isSecurityEventSource(source)) {
            boolean allowed = permissionEvaluator.hasAllPermissions(
                    op.operatorId(),
                    List.of(Permission.AUDIT_READ, Permission.SECURITY_EVENT_READ));
            if (!allowed) {
                // TODO(TASK-BE-028b): route DENIED via central aspect so this manual
                //                    call can be eliminated; record composite permission
                //                    key "audit.read+security.event.read" in admin_actions.
                throw new PermissionDeniedException(
                        "Operator lacks required permission for source=" + source);
            }
        }

        var cmd = new QueryAuditCommand(
                accountId, actionCode, from, to, source,
                page, size, idempotencyKey, reason, op);

        return ResponseEntity.ok(AuditQueryResponse.from(useCase.query(cmd)));
    }

    private static boolean isSecurityEventSource(String source) {
        return "login_history".equalsIgnoreCase(source) || "suspicious".equalsIgnoreCase(source);
    }
}
