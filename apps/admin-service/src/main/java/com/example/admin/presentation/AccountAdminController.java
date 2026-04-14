package com.example.admin.presentation;

import com.example.admin.application.AccountAdminUseCase;
import com.example.admin.application.BulkLockAccountCommand;
import com.example.admin.application.BulkLockAccountResult;
import com.example.admin.application.BulkLockAccountUseCase;
import com.example.admin.application.LockAccountCommand;
import com.example.admin.application.LockAccountResult;
import com.example.admin.application.UnlockAccountCommand;
import com.example.admin.application.UnlockAccountResult;
import com.example.admin.application.exception.ReasonRequiredException;
import com.example.admin.domain.rbac.Permission;
import com.example.admin.infrastructure.security.OperatorContextHolder;
import com.example.admin.presentation.aspect.RequiresPermission;
import com.example.admin.presentation.dto.BulkLockRequest;
import com.example.admin.presentation.dto.BulkLockResponse;
import com.example.admin.presentation.dto.LockAccountRequest;
import com.example.admin.presentation.dto.LockAccountResponse;
import com.example.admin.presentation.dto.UnlockAccountRequest;
import com.example.admin.presentation.dto.UnlockAccountResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
@Validated
public class AccountAdminController {

    private final AccountAdminUseCase useCase;
    private final BulkLockAccountUseCase bulkLockUseCase;

    @PostMapping("/{accountId}/lock")
    @RequiresPermission(Permission.ACCOUNT_LOCK)
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
    @RequiresPermission(Permission.ACCOUNT_UNLOCK)
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

    @PostMapping("/bulk-lock")
    @RequiresPermission(Permission.ACCOUNT_LOCK)
    public ResponseEntity<BulkLockResponse> bulkLock(
            @RequestHeader("X-Operator-Reason") String headerReason,
            @RequestHeader("Idempotency-Key")
            @Size(max = 64, message = "Idempotency-Key must be ≤64 characters") String idempotencyKey,
            @Valid @RequestBody BulkLockRequest body) {

        // Header reason is the audit trail; body.reason (≥8 chars) is the
        // operator-facing justification persisted to admin_actions. Both must
        // be present, matching the single-lock contract.
        if (headerReason == null || headerReason.isBlank()) {
            throw new ReasonRequiredException();
        }

        BulkLockAccountResult r = bulkLockUseCase.execute(new BulkLockAccountCommand(
                body.accountIds(),
                body.reason(),
                body.ticketId(),
                idempotencyKey,
                OperatorContextHolder.require()));

        List<BulkLockResponse.ResultItem> items = new ArrayList<>(r.results().size());
        for (var it : r.results()) {
            BulkLockResponse.ErrorDetail err = it.errorCode() == null ? null
                    : new BulkLockResponse.ErrorDetail(it.errorCode(), it.errorMessage());
            items.add(new BulkLockResponse.ResultItem(it.accountId(), it.outcome(), err));
        }
        return ResponseEntity.ok(new BulkLockResponse(items));
    }

    private static String resolveReason(String headerReason, String bodyReason) {
        if (headerReason != null && !headerReason.isBlank()) return headerReason;
        if (bodyReason != null && !bodyReason.isBlank()) return bodyReason;
        throw new ReasonRequiredException();
    }
}
