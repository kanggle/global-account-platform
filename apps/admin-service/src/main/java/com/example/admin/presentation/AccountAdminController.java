package com.example.admin.presentation;

import com.example.admin.application.AccountAdminUseCase;
import com.example.admin.application.LockAccountCommand;
import com.example.admin.application.LockAccountResult;
import com.example.admin.application.UnlockAccountCommand;
import com.example.admin.application.UnlockAccountResult;
import com.example.admin.application.exception.ReasonRequiredException;
import com.example.admin.infrastructure.security.OperatorContextHolder;
import com.example.admin.presentation.dto.LockAccountRequest;
import com.example.admin.presentation.dto.LockAccountResponse;
import com.example.admin.presentation.dto.UnlockAccountRequest;
import com.example.admin.presentation.dto.UnlockAccountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AccountAdminController {

    private final AccountAdminUseCase useCase;

    @PostMapping("/{accountId}/lock")
    @PreAuthorize("hasAnyRole('ACCOUNT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<LockAccountResponse> lock(
            @PathVariable String accountId,
            @RequestHeader(value = "X-Operator-Reason", required = false) String headerReason,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) LockAccountRequest body) {

        String reason = resolveReason(headerReason, body == null ? null : body.reason());
        String ticketId = body == null ? null : body.ticketId();
        LockAccountResult r = useCase.lock(new LockAccountCommand(
                accountId, reason, ticketId, idempotencyKey, OperatorContextHolder.require()));
        return ResponseEntity.ok(new LockAccountResponse(
                r.accountId(), r.previousStatus(), r.currentStatus(),
                r.operatorId(), r.lockedAt(), r.auditId()));
    }

    @PostMapping("/{accountId}/unlock")
    @PreAuthorize("hasAnyRole('ACCOUNT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<UnlockAccountResponse> unlock(
            @PathVariable String accountId,
            @RequestHeader(value = "X-Operator-Reason", required = false) String headerReason,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) UnlockAccountRequest body) {

        String reason = resolveReason(headerReason, body == null ? null : body.reason());
        String ticketId = body == null ? null : body.ticketId();
        UnlockAccountResult r = useCase.unlock(new UnlockAccountCommand(
                accountId, reason, ticketId, idempotencyKey, OperatorContextHolder.require()));
        return ResponseEntity.ok(new UnlockAccountResponse(
                r.accountId(), r.previousStatus(), r.currentStatus(),
                r.operatorId(), r.unlockedAt(), r.auditId()));
    }

    private static String resolveReason(String headerReason, String bodyReason) {
        if (headerReason != null && !headerReason.isBlank()) return headerReason;
        if (bodyReason != null && !bodyReason.isBlank()) return bodyReason;
        throw new ReasonRequiredException();
    }
}
