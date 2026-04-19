package com.example.auth.application;

import com.example.auth.application.command.CreateCredentialCommand;
import com.example.auth.application.exception.CredentialAlreadyExistsException;
import com.example.auth.application.result.CreateCredentialResult;
import com.example.auth.domain.credentials.Credential;
import com.example.auth.domain.credentials.CredentialHash;
import com.example.auth.domain.repository.CredentialRepository;
import com.gap.security.password.PasswordHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Use case for creating a credential row in auth_db.credentials.
 *
 * <p>Called from the internal endpoint {@code POST /internal/auth/credentials},
 * which account-service invokes during signup (TASK-BE-063 Option A).</p>
 *
 * <p>The password is argon2id-hashed here — plain text is never persisted and
 * must never be logged.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateCredentialUseCase {

    private final CredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;

    @Transactional
    public CreateCredentialResult execute(CreateCredentialCommand command) {
        String accountId = command.accountId();
        String email = Credential.normalizeEmail(command.email());

        // Cheap pre-check so we can return 409 without burning argon2id CPU on
        // duplicate calls (signup retries, at-least-once retries, etc.).
        if (credentialRepository.existsByAccountId(accountId)) {
            throw new CredentialAlreadyExistsException(accountId);
        }

        String hash = passwordHasher.hash(command.password());
        Instant now = Instant.now();
        Credential credential = Credential.create(
                accountId,
                email,
                CredentialHash.argon2id(hash),
                now
        );

        try {
            Credential saved = credentialRepository.save(credential);
            log.info("Credential created for accountId={}", saved.getAccountId());
            return new CreateCredentialResult(saved.getAccountId(), saved.getCreatedAt());
        } catch (DataIntegrityViolationException e) {
            // Concurrent create — unique constraint on account_id or email kicked in.
            // Translated to 409 (idempotent shape of a duplicate signup request).
            throw new CredentialAlreadyExistsException(accountId);
        }
    }
}
