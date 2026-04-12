package com.example.account.presentation.dto.response;

public record CredentialLookupResponse(
        String accountId,
        String credentialHash,
        String hashAlgorithm,
        String accountStatus
) {
}
