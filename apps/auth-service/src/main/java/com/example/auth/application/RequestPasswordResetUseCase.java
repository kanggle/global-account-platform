package com.example.auth.application;

import com.example.auth.application.command.RequestPasswordResetCommand;
import com.example.auth.application.port.EmailSenderPort;
import com.example.auth.domain.credentials.Credential;
import com.example.auth.domain.repository.CredentialRepository;
import com.example.auth.domain.repository.PasswordResetAttemptCounter;
import com.example.auth.domain.repository.PasswordResetTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case for {@code POST /api/auth/password-reset/request} (TASK-BE-108).
 *
 * <p>Behaviour contract:
 * <ol>
 *   <li>Look up the credential by normalized email (lower-case + trim).</li>
 *   <li>If no credential exists, return silently — the controller will reply
 *       with the same 204 it does for the existing-account case so the API
 *       does not leak account existence.</li>
 *   <li>If a credential exists, mint a UUID v4 token, persist
 *       {@code token → accountId} in Redis with a 1-hour TTL, then trigger
 *       the email send.</li>
 * </ol>
 *
 * <p>Email send failures are swallowed at WARN level (without the token
 * payload) so a transient SMTP/SES outage does not surface as a user-facing
 * 500. Redis failures, by contrast, propagate — see
 * {@code RedisPasswordResetTokenStore} for the rationale.</p>
 *
 * <p><b>Rate limiting (TASK-BE-144):</b> every request increments a per-email
 * counter regardless of whether the credential exists. When the counter
 * exceeds the configured threshold the use case returns silently — same shape
 * as the not-found path so that response timing/shape does not leak account
 * existence or rate-limit state. The counter is keyed on the SHA-256-truncated
 * email hash (PII-safe), shared with {@code LoginUseCase.hashEmail}.</p>
 *
 * <p>Marked {@code @Transactional(readOnly = true)} purely for the
 * credential lookup; the Redis write is non-transactional by nature and
 * the email send is best-effort.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestPasswordResetUseCase {

    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final CredentialRepository credentialRepository;
    private final PasswordResetTokenStore passwordResetTokenStore;
    private final PasswordResetAttemptCounter rateLimitCounter;
    private final EmailSenderPort emailSenderPort;

    @Transactional(readOnly = true)
    public void execute(RequestPasswordResetCommand command) {
        // Use the same normalization the credential row was stored with so the
        // lookup matches whatever casing/whitespace the user typed.
        String normalizedEmail = Credential.normalizeEmail(command.email());

        // Rate limit FIRST — counter increments regardless of credential
        // existence. Mirrors the silent-no-op pattern below to avoid leaking
        // account existence via response timing or status differences.
        String emailHash = hashEmail(normalizedEmail);
        if (!rateLimitCounter.tryAcquire(emailHash)) {
            log.info("password-reset rate limit exceeded for emailHash={}", emailHash);
            return;
        }

        Optional<Credential> maybeCredential =
                credentialRepository.findByAccountIdEmail(normalizedEmail);
        if (maybeCredential.isEmpty()) {
            // Silent no-op — do not leak email existence (R4 / saas auth ux rule).
            return;
        }

        Credential credential = maybeCredential.get();
        String token = UUID.randomUUID().toString();
        passwordResetTokenStore.save(token, credential.getAccountId(), TOKEN_TTL);

        try {
            emailSenderPort.sendPasswordResetEmail(normalizedEmail, token);
        } catch (Exception e) {
            // Best-effort: Redis write already succeeded so the user will be
            // able to use the token if they receive it via another channel
            // (e.g. retry from the client). Do NOT log the token here — only
            // the dev stub may emit it (at INFO).
            log.warn("Failed to send password reset email for accountId={}: {}",
                    credential.getAccountId(), e.getMessage());
        }
    }

    /**
     * 10-character SHA-256 truncation of the lower-cased email. Mirrors
     * {@code LoginUseCase.hashEmail} so a single login-flood key prefix and a
     * single password-reset-flood key prefix can both reference the same
     * stable identifier without storing the raw email.
     */
    static String hashEmail(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(email.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
