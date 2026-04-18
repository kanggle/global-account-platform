package com.example.account.infrastructure.persistence;

import com.example.account.domain.account.Account;
import com.example.account.domain.status.AccountStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
    private int version;

    public static AccountJpaEntity fromDomain(Account account) {
        AccountJpaEntity entity = new AccountJpaEntity();
        entity.id = account.getId();
        entity.email = account.getEmail();
        entity.emailHash = account.getEmailHash();
        entity.status = account.getStatus();
        entity.createdAt = account.getCreatedAt();
        entity.updatedAt = account.getUpdatedAt();
        entity.deletedAt = account.getDeletedAt();
        entity.version = account.getVersion();
        return entity;
    }

    public Account toDomain() {
        return Account.reconstitute(id, email, emailHash, status, createdAt, updatedAt, deletedAt, version);
    }
}
