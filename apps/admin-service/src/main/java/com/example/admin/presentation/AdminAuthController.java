package com.example.admin.presentation;

import com.example.admin.application.ActionCode;
import com.example.admin.application.AdminActionAuditor;
import com.example.admin.application.OperatorContext;
import com.example.admin.application.Outcome;
import com.example.admin.application.TotpEnrollmentService;
import com.example.admin.application.exception.InvalidBootstrapTokenException;
import com.example.admin.application.exception.InvalidTwoFaCodeException;
import com.example.admin.infrastructure.security.BootstrapContext;
import com.example.admin.presentation.dto.TotpEnrollResponse;
import com.example.admin.presentation.dto.TotpVerifyRequest;
import com.example.admin.presentation.dto.TotpVerifyResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * admin-service authentication endpoints that sit BEFORE the operator JWT
 * is issued. See admin-api.md §Authentication Exceptions.
 *
 * <p>Only the 2FA enroll/verify endpoints are implemented in 029-2 — the
 * {@code /login} endpoint lands in 029-3.
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final TotpEnrollmentService totpService;
    private final AdminActionAuditor auditor;

    @PostMapping("/2fa/enroll")
    public ResponseEntity<TotpEnrollResponse> enroll(HttpServletRequest request) {
        BootstrapContext bootstrap = requireBootstrap(request);
        String operatorId = bootstrap.operatorId();
        Instant startedAt = Instant.now();
        String auditId = auditor.newAuditId();

        try {
            TotpEnrollmentService.EnrollmentResult result = totpService.enroll(operatorId);
            auditor.record(new AdminActionAuditor.AuditRecord(
                    auditId,
                    ActionCode.OPERATOR_2FA_ENROLL,
                    new OperatorContext(operatorId, bootstrap.jti()),
                    "OPERATOR",
                    operatorId,
                    AdminActionAuditor.REASON_SELF_ENROLLMENT,
                    null,
                    "bootstrap:" + bootstrap.jti(),
                    Outcome.SUCCESS,
                    null,
                    startedAt,
                    Instant.now()));
            return ResponseEntity.ok(new TotpEnrollResponse(
                    result.otpauthUri(), result.recoveryCodes(), result.enrolledAt()));
        } catch (RuntimeException ex) {
            safeRecordFailure(auditId, ActionCode.OPERATOR_2FA_ENROLL, operatorId, bootstrap.jti(),
                    ex.getClass().getSimpleName(), startedAt);
            throw ex;
        }
    }

    @PostMapping("/2fa/verify")
    public ResponseEntity<TotpVerifyResponse> verify(HttpServletRequest request,
                                                     @Valid @RequestBody TotpVerifyRequest body) {
        BootstrapContext bootstrap = requireBootstrap(request);
        String operatorId = bootstrap.operatorId();
        Instant startedAt = Instant.now();
        String auditId = auditor.newAuditId();

        try {
            totpService.verify(operatorId, body.totpCode());
            auditor.record(new AdminActionAuditor.AuditRecord(
                    auditId,
                    ActionCode.OPERATOR_2FA_VERIFY,
                    new OperatorContext(operatorId, bootstrap.jti()),
                    "OPERATOR",
                    operatorId,
                    AdminActionAuditor.REASON_SELF_ENROLLMENT,
                    null,
                    "bootstrap:" + bootstrap.jti(),
                    Outcome.SUCCESS,
                    null,
                    startedAt,
                    Instant.now()));
            return ResponseEntity.ok(new TotpVerifyResponse(true));
        } catch (InvalidTwoFaCodeException ex) {
            safeRecordFailure(auditId, ActionCode.OPERATOR_2FA_VERIFY, operatorId, bootstrap.jti(),
                    "INVALID_2FA_CODE", startedAt);
            throw ex;
        }
    }

    private void safeRecordFailure(String auditId,
                                   ActionCode actionCode,
                                   String operatorId,
                                   String jti,
                                   String detail,
                                   Instant startedAt) {
        try {
            auditor.record(new AdminActionAuditor.AuditRecord(
                    auditId,
                    actionCode,
                    new OperatorContext(operatorId, jti),
                    "OPERATOR",
                    operatorId,
                    AdminActionAuditor.REASON_SELF_ENROLLMENT,
                    null,
                    "bootstrap:" + jti + ":failed",
                    Outcome.FAILURE,
                    detail,
                    startedAt,
                    Instant.now()));
        } catch (RuntimeException ignored) {
            // Best-effort on failure path — do not mask the original exception.
        }
    }

    private static BootstrapContext requireBootstrap(HttpServletRequest request) {
        Object attr = request.getAttribute(BootstrapContext.ATTRIBUTE);
        if (attr instanceof BootstrapContext ctx) {
            return ctx;
        }
        throw new InvalidBootstrapTokenException("Bootstrap context missing on request");
    }
}
