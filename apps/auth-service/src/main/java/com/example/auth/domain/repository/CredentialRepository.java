package com.example.auth.domain.repository;

import com.example.auth.domain.credentials.Credential;

import java.util.Optional;

/**
 * Port interface for credential persistence.
 */
public interface CredentialRepository {

    Optional<Credential> findByAccountId(String accountId);

    Credential save(Credential credential);
}
