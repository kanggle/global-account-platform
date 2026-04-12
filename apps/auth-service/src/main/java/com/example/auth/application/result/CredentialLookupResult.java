package com.example.auth.application.result;

/**
 * Result of looking up credentials from account-service.
 */
public record CredentialLookupResult(
        String accountId,
        String credentialHash,
        String hashAlgorithm,
        String accountStatus
) {
}
