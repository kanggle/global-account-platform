package com.example.auth.infrastructure.persistence;

import com.example.auth.domain.credentials.Credential;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CredentialJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, unique = true, length = 36)
    private String accountId;

    @Column(name = "credential_hash", nullable = false)
    private String credentialHash;

    @Column(name = "hash_algorithm", nullable = false, length = 30)
    private String hashAlgorithm;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    public Credential toDomain() {
        return new Credential(id, accountId, credentialHash, hashAlgorithm, createdAt, updatedAt, version);
    }

    public static CredentialJpaEntity fromDomain(Credential credential) {
        CredentialJpaEntity entity = new CredentialJpaEntity();
        entity.id = credential.getId();
        entity.accountId = credential.getAccountId();
        entity.credentialHash = credential.getCredentialHash();
        entity.hashAlgorithm = credential.getHashAlgorithm();
        entity.createdAt = credential.getCreatedAt();
        entity.updatedAt = credential.getUpdatedAt();
        entity.version = credential.getVersion();
        return entity;
    }
}
