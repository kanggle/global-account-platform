package com.example.auth.domain.credentials;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain entity representing a user's credential (password hash).
 * Pure POJO - no framework annotations.
 */
public class Credential {

    private final Long id;
    private final String accountId;
    private final String credentialHash;
    private final String hashAlgorithm;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final int version;

    public Credential(Long id, String accountId, String credentialHash, String hashAlgorithm,
                      Instant createdAt, Instant updatedAt, int version) {
        this.id = id;
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.credentialHash = Objects.requireNonNull(credentialHash, "credentialHash must not be null");
        this.hashAlgorithm = Objects.requireNonNull(hashAlgorithm, "hashAlgorithm must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public Long getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCredentialHash() {
        return credentialHash;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getVersion() {
        return version;
    }
}
