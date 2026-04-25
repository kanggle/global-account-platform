package com.example.admin.presentation;

import com.example.admin.application.OperatorAdminUseCase;
import com.example.admin.application.OperatorAdminUseCase.CreateOperatorResult;
import com.example.admin.application.OperatorAdminUseCase.OperatorPage;
import com.example.admin.application.OperatorAdminUseCase.OperatorSummary;
import com.example.admin.application.OperatorAdminUseCase.PatchRolesResult;
import com.example.admin.application.OperatorAdminUseCase.PatchStatusResult;
import com.example.admin.application.exception.ReasonRequiredException;
import com.example.admin.domain.rbac.Permission;
import com.example.admin.infrastructure.security.OperatorContextHolder;
import com.example.admin.presentation.aspect.RequiresPermission;
import com.example.admin.presentation.aspect.SelfServiceEndpoint;
import com.example.admin.presentation.dto.ChangeMyPasswordRequest;
import com.example.admin.presentation.dto.CreateOperatorRequest;
import com.example.admin.presentation.dto.CreateOperatorResponse;
import com.example.admin.presentation.dto.OperatorListResponse;
import com.example.admin.presentation.dto.OperatorSummaryResponse;
import com.example.admin.presentation.dto.PatchOperatorRolesRequest;
import com.example.admin.presentation.dto.PatchOperatorRolesResponse;
import com.example.admin.presentation.dto.PatchOperatorStatusRequest;
import com.example.admin.presentation.dto.PatchOperatorStatusResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * TASK-BE-083 — operator management HTTP surface. Matches the 5 endpoints in
 * {@code specs/contracts/http/admin-api.md} and mirrors the layering of
 * {@link AccountAdminController} / {@link AdminGdprController}.
 *
 * <p>Authorisation uses the central {@link RequiresPermission} aspect; the
 * {@code GET /api/admin/me} endpoint is authenticated-only (operator JWT
 * required) and so carries no permission annotation — the upstream
 * {@code OperatorAuthenticationFilter} enforces authentication, and this
 * endpoint is a GET (exempt from the aspect's deny-by-default mutation rule).
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Validated
public class OperatorAdminController {

    private final OperatorAdminUseCase useCase;

    // ------------------------------------------------------------------ GET /me

    @GetMapping("/me")
    public ResponseEntity<OperatorSummaryResponse> currentOperator() {
        String operatorId = OperatorContextHolder.require().operatorId();
        OperatorSummary summary = useCase.getCurrentOperator(operatorId);
        return ResponseEntity.ok(toResponse(summary));
    }

    // --------------------------------------------------------- GET /operators

    @GetMapping("/operators")
    @RequiresPermission(Permission.OPERATOR_MANAGE)
    public ResponseEntity<OperatorListResponse> listOperators(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        OperatorPage result = useCase.listOperators(status, page, size);
        List<OperatorSummaryResponse> items = new ArrayList<>(result.content().size());
        for (OperatorSummary s : result.content()) {
            items.add(toResponse(s));
        }
        return ResponseEntity.ok(new OperatorListResponse(
                items,
                result.totalElements(),
                result.page(),
                result.size(),
                result.totalPages()));
    }

    // -------------------------------------------------------- POST /operators

    @PostMapping("/operators")
    @RequiresPermission(Permission.OPERATOR_MANAGE)
    public ResponseEntity<CreateOperatorResponse> createOperator(
            @RequestHeader(value = "X-Operator-Reason", required = false) String headerReason,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOperatorRequest body) {

        String reason = requireReason(headerReason);

        CreateOperatorResult result = useCase.createOperator(
                body.email(),
                body.displayName(),
                body.password(),
                body.roles(),
                OperatorContextHolder.require(),
                reason);

        CreateOperatorResponse response = new CreateOperatorResponse(
                result.operatorId(),
                result.email(),
                result.displayName(),
                result.status(),
                result.roles(),
                result.totpEnrolled(),
                result.createdAt(),
                result.auditId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // --------------------------------- PATCH /operators/{operatorId}/roles

    @PatchMapping("/operators/{operatorId}/roles")
    @RequiresPermission(Permission.OPERATOR_MANAGE)
    public ResponseEntity<PatchOperatorRolesResponse> patchRoles(
            @PathVariable String operatorId,
            @RequestHeader(value = "X-Operator-Reason", required = false) String headerReason,
            @Valid @RequestBody PatchOperatorRolesRequest body) {

        String reason = requireReason(headerReason);

        PatchRolesResult result = useCase.patchRoles(
                operatorId,
                body.roles(),
                OperatorContextHolder.require(),
                reason);

        return ResponseEntity.ok(new PatchOperatorRolesResponse(
                result.operatorId(), result.roles(), result.auditId()));
    }

    // -------------------------------- PATCH /operators/{operatorId}/status

    @PatchMapping("/operators/{operatorId}/status")
    @RequiresPermission(Permission.OPERATOR_MANAGE)
    public ResponseEntity<PatchOperatorStatusResponse> patchStatus(
            @PathVariable String operatorId,
            @RequestHeader(value = "X-Operator-Reason", required = false) String headerReason,
            @Valid @RequestBody PatchOperatorStatusRequest body) {

        String reason = requireReason(headerReason);

        PatchStatusResult result = useCase.patchStatus(
                operatorId,
                body.status(),
                OperatorContextHolder.require(),
                reason);

        return ResponseEntity.ok(new PatchOperatorStatusResponse(
                result.operatorId(),
                result.previousStatus(),
                result.currentStatus(),
                result.auditId()));
    }

    // ----------------------------- PATCH /operators/me/password

    @PatchMapping("/operators/me/password")
    @SelfServiceEndpoint
    public ResponseEntity<Void> changeMyPassword(
            @Valid @RequestBody ChangeMyPasswordRequest body) {

        String operatorId = OperatorContextHolder.require().operatorId();
        useCase.changeMyPassword(operatorId, body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- helpers

    private static OperatorSummaryResponse toResponse(OperatorSummary summary) {
        return new OperatorSummaryResponse(
                summary.operatorId(),
                summary.email(),
                summary.displayName(),
                summary.status(),
                summary.roles(),
                summary.totpEnrolled(),
                summary.lastLoginAt(),
                summary.createdAt());
    }

    private static String requireReason(String headerReason) {
        if (headerReason == null || headerReason.isBlank()) {
            throw new ReasonRequiredException();
        }
        return headerReason;
    }
}
