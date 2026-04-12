package com.example.auth.application.port;

import com.example.auth.application.result.CredentialLookupResult;

import java.util.Optional;

/**
 * Port interface for communicating with account-service.
 * Implementation lives in infrastructure/client/.
 */
public interface AccountServicePort {

    /**
     * Looks up credentials by email via internal HTTP to account-service.
     *
     * @return credential info including account status, or empty if no account found
     * @throws com.example.auth.application.exception.AccountServiceUnavailableException if account-service is down
     */
    Optional<CredentialLookupResult> lookupCredentialsByEmail(String email);
}
