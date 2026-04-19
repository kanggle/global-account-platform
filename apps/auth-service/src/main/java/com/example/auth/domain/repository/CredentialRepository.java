package com.example.auth.domain.repository;

import com.example.auth.domain.credentials.Credential;

import java.util.Optional;

/**
 * Port interface for credential persistence.
 */
public interface CredentialRepository {

    Optional<Credential> findByAccountId(String accountId);

    /**
     * Resolve a credential by login email. Implementations normalize the email
     * (lower-case + trim) before querying; callers may pass the raw input.
     *
     * <p>Introduced by TASK-BE-063 so the login path does not need to round-trip
     * to account-service just to resolve email → accountId.</p>
     */
    Optional<Credential> findByAccountIdEmail(String email);

    Credential save(Credential credential);

    /**
     * Whether a credential row already exists for the given accountId. Used by
     * the internal credential-create endpoint to return 409 on duplicate before
     * attempting an insert (argon2id hashing is expensive, so we short-circuit).
     */
    boolean existsByAccountId(String accountId);
}
