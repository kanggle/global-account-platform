package com.example.admin.presentation;

import com.example.admin.application.ActionCode;
import com.example.admin.application.AdminActionAuditor;
import com.example.admin.application.AdminLoginService;
import com.example.admin.application.AdminLogoutService;
import com.example.admin.application.AdminRefreshTokenService;
import com.example.admin.application.OperatorContext;
import com.example.admin.application.Outcome;
import com.example.admin.application.TotpEnrollmentService;
import com.example.admin.application.exception.EnrollmentRequiredException;
import com.example.admin.application.exception.InvalidBootstrapTokenException;
import com.example.admin.application.exception.InvalidCredentialsException;
import com.example.admin.application.exception.InvalidLoginRequestException;
import com.example.admin.application.exception.InvalidRecoveryCodeException;
import com.example.admin.application.exception.InvalidRefreshTokenException;
import com.example.admin.application.exception.InvalidTwoFaCodeException;
import com.example.admin.application.exception.OperatorUnauthorizedException;
import com.example.admin.application.exception.RefreshTokenReuseDetectedException;
import com.example.admin.infrastructure.security.BootstrapContext;
import com.example.admin.infrastructure.security.OperatorAuthenticationFilter;
import com.example.admin.presentation.dto.AdminLoginRequest;
import com.example.admin.presentation.dto.AdminLoginResponse;
import com.example.admin.presentation.dto.AdminLogoutRequest;
import com.example.admin.presentation.dto.AdminRefreshRequest;
import com.example.admin.presentation.dto.AdminRefreshResponse;
import com.example.admin.presentation.dto.TotpEnrollResponse;
import com.example.admin.presentation.dto.TotpVerifyRequest;
import com.example.admin.presentation.dto.TotpVerifyResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final AdminRefreshTokenService refreshService;
    private final AdminLogoutService logoutService;
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
            return ResponseEntity.ok(new AdminLoginResponse(
                    result.accessToken(),
                    result.expiresIn(),
                    result.refreshToken(),
                    result.refreshExpiresIn()));
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

    /**
     * TASK-BE-040 — refresh-token rotation. Unauthenticated like /login but
     * presents an existing refresh JWT in the body; rotates it and returns a
     * new access+refresh pair. Reuse of an already-rotated jti triggers
     * bulk-revocation of the operator's chain (REUSE_DETECTED).
     */
    @PostMapping("/refresh")
    public ResponseEntity<AdminRefreshResponse> refresh(@Valid @RequestBody AdminRefreshRequest body) {
        Instant startedAt = Instant.now();
        String auditId = auditor.newAuditId();
        String operatorIdForAudit = "<unknown>";
        try {
            AdminRefreshTokenService.RefreshResult result = refreshService.refresh(body.refreshToken());
            // sub claim was validated inside the service — re-derive operator id from the
            // SecurityContext if available, otherwise leave the audit row's operator
            // resolved through the JWT path (auditor.recordLogin uses the supplied id).
            // Because /refresh runs with no SecurityContext, we depend on the service to
            // populate the new tokens; the only operator id we can audit is the one
            // returned via the new access token's sub. For a single-shot audit it is
            // enough to extract it from the request body's refresh token claim — but
            // the service already rejected invalid tokens, so we re-parse here is
            // unnecessary. Use the result's underlying operator via re-verification by
            // the service is overkill: instead, capture it from the rotated chain via
            // the response's accessToken sub. For audit, we record the operator id by
            // decoding the new access token's claims is also overkill; here we keep the
            // detail compact. The service emits a structured log with the operator id;
            // the audit row uses target_id from the SecurityContext when present and
            // otherwise "<unknown>" — acceptable for SUCCESS audit completeness on the
            // unauthenticated sub-tree.
            operatorIdForAudit = extractOperatorIdSafely(body.refreshToken());
            safeRecordSession(auditId, ActionCode.OPERATOR_REFRESH, operatorIdForAudit,
                    Outcome.SUCCESS, null, AdminActionAuditor.REASON_SELF_REFRESH,
                    "refresh:" + auditId, startedAt);
            return ResponseEntity.ok(new AdminRefreshResponse(
                    result.accessToken(), result.expiresIn(),
                    result.refreshToken(), result.refreshExpiresIn()));
        } catch (RefreshTokenReuseDetectedException ex) {
            operatorIdForAudit = extractOperatorIdSafely(body.refreshToken());
            safeRecordSession(auditId, ActionCode.OPERATOR_REFRESH, operatorIdForAudit,
                    Outcome.FAILURE, "REUSE_DETECTED", AdminActionAuditor.REASON_SELF_REFRESH,
                    "refresh:" + auditId + ":reuse", startedAt);
            throw ex;
        } catch (InvalidRefreshTokenException ex) {
            // Best-effort decode for audit; if the token is so malformed that we can't
            // even read sub, fall back to "<unknown>".
            operatorIdForAudit = extractOperatorIdSafely(body.refreshToken());
            safeRecordSession(auditId, ActionCode.OPERATOR_REFRESH, operatorIdForAudit,
                    Outcome.FAILURE, "INVALID_REFRESH_TOKEN", AdminActionAuditor.REASON_SELF_REFRESH,
                    "refresh:" + auditId + ":invalid", startedAt);
            throw ex;
        }
    }

    /**
     * TASK-BE-040 — operator self-logout. Authenticated path: requires a valid
     * operator access JWT. Blacklists the access jti and (optionally) revokes
     * a supplied refresh token. Returns 204.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                       @RequestBody(required = false) AdminLogoutRequest body) {
        OperatorContext op = currentOperator();
        if (op == null) {
            throw new OperatorUnauthorizedException("Operator JWT required for logout");
        }
        Instant startedAt = Instant.now();
        String auditId = auditor.newAuditId();
        Instant accessExp = (Instant) request.getAttribute(OperatorAuthenticationFilter.ACCESS_EXP_ATTRIBUTE);
        String refreshToken = body != null ? body.refreshToken() : null;
        try {
            logoutService.logout(op.operatorId(), op.jti(), accessExp, refreshToken);
            safeRecordSession(auditId, ActionCode.OPERATOR_LOGOUT, op.operatorId(),
                    Outcome.SUCCESS, null, AdminActionAuditor.REASON_SELF_LOGOUT,
                    "logout:" + auditId, startedAt);
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        } catch (RuntimeException ex) {
            safeRecordSession(auditId, ActionCode.OPERATOR_LOGOUT, op.operatorId(),
                    Outcome.FAILURE, ex.getClass().getSimpleName(), AdminActionAuditor.REASON_SELF_LOGOUT,
                    "logout:" + auditId + ":failed", startedAt);
            throw ex;
        }
    }

    private static OperatorContext currentOperator() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof OperatorContext ctx) {
                return ctx;
            }
        } catch (RuntimeException ignored) {
            // no security context
        }
        return null;
    }

    /**
     * Extracts {@code sub} from the JWT payload without verifying the
     * signature — used purely for audit row enrichment. The signature path
     * has already either accepted or rejected the token in the surrounding
     * service call; if decoding fails we fall back to {@code "<unknown>"}.
     */
    private static String extractOperatorIdSafely(String jwt) {
        try {
            if (jwt == null) return "<unknown>";
            int dot1 = jwt.indexOf('.');
            int dot2 = jwt.indexOf('.', dot1 + 1);
            if (dot1 < 0 || dot2 < 0) return "<unknown>";
            String payload = jwt.substring(dot1 + 1, dot2);
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(payload);
            String json = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            int idx = json.indexOf("\"sub\"");
            if (idx < 0) return "<unknown>";
            int colon = json.indexOf(':', idx);
            int q1 = json.indexOf('"', colon + 1);
            int q2 = json.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) return "<unknown>";
            return json.substring(q1 + 1, q2);
        } catch (RuntimeException ignored) {
            return "<unknown>";
        }
    }

    /**
     * Single-shot audit write for /refresh and /logout. Mirrors
     * {@link #safeRecordLogin} fail-closed semantics: SUCCESS path propagates
     * audit failures (A10), FAILURE path swallows secondary audit errors so
     * the original 401/500 user-visible status is preserved (architecture.md
     * Overrides A10).
     */
    private void safeRecordSession(String auditId,
                                   ActionCode actionCode,
                                   String operatorId,
                                   Outcome outcome,
                                   String detail,
                                   String reason,
                                   String idempotencyKey,
                                   Instant startedAt) {
        try {
            auditor.record(new AdminActionAuditor.AuditRecord(
                    auditId,
                    actionCode,
                    new OperatorContext(operatorId, null),
                    "OPERATOR",
                    operatorId,
                    reason,
                    null,
                    idempotencyKey,
                    outcome,
                    detail,
                    startedAt,
                    Instant.now()));
        } catch (RuntimeException ex) {
            if (outcome == Outcome.SUCCESS) {
                throw ex;
            }
            // FAILURE path: swallow secondary audit error
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

    /**
     * Records the login audit row. Success path propagates
     * {@code AuditFailureException} (fail-closed per audit-heavy A10).
     * FAILURE path swallows secondary audit errors so the original 401/400
     * user-visible status is not masked — this is a documented override
     * against A10. See architecture.md#Overrides A10.
     */
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
