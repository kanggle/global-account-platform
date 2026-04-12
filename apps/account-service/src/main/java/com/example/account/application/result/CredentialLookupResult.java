package com.example.account.application.result;

public record CredentialLookupResult(
        String accountId,
        String credentialHash,
        String hashAlgorithm,
        String accountStatus
) {
}
