package com.example.auth.application;

import com.example.auth.application.command.ConfirmPasswordResetCommand;
import com.example.auth.application.exception.PasswordResetTokenInvalidException;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.domain.credentials.Credential;
import com.example.auth.domain.credentials.CredentialHash;
import com.example.auth.domain.credentials.PasswordPolicy;
import com.example.auth.domain.repository.BulkInvalidationStore;
import com.example.auth.domain.repository.CredentialRepository;
import com.example.auth.domain.repository.PasswordResetTokenStore;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.gap.security.password.PasswordHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Use case for {@code POST /api/auth/password-reset/confirm} (TASK-BE-109).
 *
 * <p>Consumes a previously-issued password reset token, validates the new
 * password against {@link PasswordPolicy}, persists the new credential hash,
 * revokes every refresh token + sets a bulk-invalidation marker for the
 * account, and finally deletes the token from Redis to enforce single-use
 * semantics.</p>
 *
 * <p><strong>Ordering matters.</strong> The token is deleted <em>last</em>:
 * if persisting the new hash or the session revoke fails, the transaction
 * rolls back and the token must remain valid in Redis so the user can retry.
 * See task spec "Failure Scenarios".</p>
 *
 * <p>R4 (rules/traits/regulated.md): the plaintext password and the reset
 * token are never logged. The only INFO log line identifies the {@code accountId}
 * and the count of revoked tokens.</p>
 *
 * <p>Failure modes:
 * <ul>
 *   <li>token unknown / expired → {@link PasswordResetTokenInvalidException}</li>
 *   <li>credential row missing for the resolved account →
 *       {@link PasswordResetTokenInvalidException} (uniform response per spec
 *       Edge Case "계정이 삭제된 경우")</li>
 *   <li>{@code newPassword} fails {@link PasswordPolicy} →
 *       {@code PasswordPolicyViolationException} (mapped to 400
 *       {@code PASSWORD_POLICY_VIOLATION})</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmPasswordResetUseCase {

    private final PasswordResetTokenStore passwordResetTokenStore;
    private final CredentialRepository credentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BulkInvalidationStore bulkInvalidationStore;
    private final TokenGeneratorPort tokenGeneratorPort;
    private final PasswordHasher passwordHasher;

    @Transactional
    public void execute(ConfirmPasswordResetCommand command) {
        // 1) Resolve the token. Missing/expired/already-used all surface as the
        //    same exception so the API does not leak token state.
        String accountId = passwordResetTokenStore.findAccountId(command.token())
                .orElseThrow(PasswordResetTokenInvalidException::new);

        // 2) Locate the credential row. If the account was deleted between
        //    request and confirm, treat it as an invalid token (uniform response).
        Credential credential = credentialRepository.findByAccountId(accountId)
                .orElseThrow(PasswordResetTokenInvalidException::new);

        // 3) Validate against the password policy. PolicyViolationException
        //    intentionally never includes the password value (R4).
        PasswordPolicy.validate(command.newPassword(), credential.getEmail());

        // 4) Persist the new hash. Argon2id is expensive — only run after the
        //    cheaper checks above have passed.
        String newHash = passwordHasher.hash(command.newPassword());
        Credential updated = credential.changePassword(
                CredentialHash.argon2id(newHash), Instant.now());
        credentialRepository.save(updated);

        // 5) Revoke every active refresh token for the account and set a
        //    bulk-invalidation marker so any in-flight refresh attempts with
        //    a token issued before this instant fail closed. Both calls are
        //    idempotent and independently safe (see ForceLogoutUseCase).
        int revokedCount = refreshTokenRepository.revokeAllByAccountId(accountId);
        bulkInvalidationStore.invalidateAll(
                accountId, tokenGeneratorPort.refreshTokenTtlSeconds());

        // 6) Single-use enforcement: delete the token AFTER all DB writes have
        //    succeeded. If any of steps 4–5 fails the transaction rolls back
        //    and the token must remain valid for retry.
        passwordResetTokenStore.delete(command.token());

        log.info("Password reset confirmed for accountId={}, revokedTokens={}",
                accountId, revokedCount);
    }
}
