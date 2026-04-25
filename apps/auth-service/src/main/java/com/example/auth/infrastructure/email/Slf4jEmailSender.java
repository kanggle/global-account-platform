package com.example.auth.infrastructure.email;

import com.example.auth.application.port.EmailSenderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Development-only {@link EmailSenderPort} stub.
 *
 * <p>This adapter exists so the password-reset flow can be exercised end-to-end
 * without an SMTP/SES connection. It logs the would-be recipient and the
 * generated reset token at INFO level so the developer can copy it into the
 * confirm endpoint manually.</p>
 *
 * <p><strong>Production note (TASK-BE-108):</strong> a real implementation must
 * be supplied before this service is deployed to any non-development
 * environment. The real bean should be registered as
 * {@code @Component("realEmailSender")} so that this stub's
 * {@link ConditionalOnMissingBean} guard backs off automatically. Do
 * <strong>not</strong> remove the {@code @ConditionalOnMissingBean} — it is the
 * mechanism that prevents this stub from quietly co-existing with a real
 * sender and leaking tokens to logs.</p>
 *
 * <p>Tokens are logged at INFO only; no other log level emits the token or
 * recipient address (R4: no plaintext credentials or sensitive PII in
 * higher-severity logs).</p>
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "realEmailSender")
public class Slf4jEmailSender implements EmailSenderPort {

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        log.info("[DEV STUB] Password reset email — to={}, token={}", toEmail, resetToken);
    }
}
