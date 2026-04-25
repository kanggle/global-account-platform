package com.example.auth.application.port;

/**
 * Port interface for outbound transactional email.
 *
 * <p>Currently scoped to password-reset notifications only — additional flows
 * (signup verification, suspicious-login alerts, etc.) should add new methods
 * here when they land. The single-method shape keeps the contract narrow so
 * the production SMTP/SES adapter can be substituted without re-exposing
 * unrelated email surface area to the application layer.</p>
 *
 * <p>Implementations live in {@code infrastructure/email/}. During development
 * the {@code Slf4jEmailSender} stub is registered automatically; a real
 * implementation should be marked {@code @Component} and named
 * {@code "realEmailSender"} so the stub backs off (see TASK-BE-108).</p>
 */
public interface EmailSenderPort {

    /**
     * Send a password reset email. The implementation is expected to render
     * a link from {@code resetToken} (e.g. {@code /password-reset?token=...})
     * before sending.
     *
     * <p>Callers must treat send failures as best-effort: the use case should
     * catch and log exceptions rather than aborting the request, since the
     * controller surfaces a uniform 204 regardless of whether the address
     * exists.</p>
     */
    void sendPasswordResetEmail(String toEmail, String resetToken);
}
