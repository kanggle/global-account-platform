package com.example.admin.presentation;

import com.example.admin.application.ActionCode;
import com.example.admin.application.AdminActionAuditor;
import com.example.admin.application.AdminLoginService;
import com.example.admin.application.OperatorContext;
import com.example.admin.application.Outcome;
import com.example.admin.application.TotpEnrollmentService;
import com.example.admin.application.exception.EnrollmentRequiredException;
import com.example.admin.application.exception.InvalidBootstrapTokenException;
import com.example.admin.application.exception.InvalidCredentialsException;
import com.example.admin.application.exception.InvalidLoginRequestException;
import com.example.admin.application.exception.InvalidRecoveryCodeException;
import com.example.admin.application.exception.InvalidTwoFaCodeException;
import com.example.admin.infrastructure.security.BootstrapContext;
import com.example.admin.presentation.dto.AdminLoginRequest;
import com.example.admin.presentation.dto.AdminLoginResponse;
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
 * <p>029-2 shipped the 2FA enroll/verify endpoints (bootstrap-token-auth);
 * 029-3 adds {@code /login} which accepts password + optional 2FA and mints
 * the operator JWT (or a bootstrap token when enrollment is outstanding).
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final TotpEnrollmentService totpService;
    private final AdminLoginService loginService;
    private final AdminActionAuditor auditor;

    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest body) {
        Instant startedAt = Instant.now();
        String auditId = auditor.newAuditId();
        String operatorId = body.operatorId();
        String idempotencyKey = "login:" + auditId;

        try {
            AdminLoginService.LoginResult result = loginService.login(
                    operatorId, body.password(), body.totpCode(), body.recoveryCode());
            safeRecordLogin(auditId, operatorId, Outcome.SUCCESS, null,
                    result.twofaUsed(), idempotencyKey, startedAt);
            return ResponseEntity.ok(new AdminLoginResponse(result.accessToken(), result.expiresIn()));
        } catch (InvalidCredentialsException ex) {
            safeRecordLogin(auditId, operatorId, Outcome.FAILURE, "INVALID_CREDENTIALS",
                    false, idempotencyKey, startedAt);
            throw ex;
        } catch (EnrollmentRequiredException ex) {
            safeRecordLogin(auditId, operatorId, Outcome.FAILURE, "ENROLLMENT_REQUIRED",
                    false, idempotencyKey, startedAt);
            throw ex;
        } catch (InvalidTwoFaCodeException ex) {
            safeRecordLogin(auditId, operatorId, Outcome.FAILURE, "INVALID_2FA_CODE",
                    false, idempotencyKey, startedAt);
            throw ex;
        } catch (InvalidRecoveryCodeException ex) {
            safeRecordLogin(auditId, operatorId, Outcome.FAILURE, "INVALID_RECOVERY_CODE",
                    false, idempotencyKey, startedAt);
            throw ex;
        } catch (InvalidLoginRequestException ex) {
            safeRecordLogin(auditId, operatorId, Outcome.FAILURE, "BAD_REQUEST",
                    false, idempotencyKey, startedAt);
            throw ex;
        }
    }

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

    private void safeRecordLogin(String auditId,
                                 String operatorId,
                                 Outcome outcome,
                                 String detail,
                                 boolean twofaUsed,
                                 String idempotencyKey,
                                 Instant startedAt) {
        try {
            auditor.recordLogin(new AdminActionAuditor.LoginAuditRecord(
                    auditId,
                    new OperatorContext(operatorId, null),
                    "OPERATOR",
                    operatorId,
                    AdminActionAuditor.REASON_SELF_LOGIN,
                    outcome == Outcome.SUCCESS ? idempotencyKey : idempotencyKey + ":failed",
                    outcome,
                    detail,
                    twofaUsed,
                    startedAt,
                    Instant.now()));
        } catch (RuntimeException ex) {
            // Audit fail-closed is already enforced inside recordLogin for the
            // success path (AuditFailureException propagates). On FAILURE paths
            // we intentionally swallow secondary audit errors so the original
            // login failure (401/400) is not masked.
            if (outcome == Outcome.SUCCESS) {
                throw ex;
            }
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
