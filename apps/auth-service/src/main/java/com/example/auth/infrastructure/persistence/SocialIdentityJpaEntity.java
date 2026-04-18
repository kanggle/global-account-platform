package com.example.auth.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "social_identities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialIdentityJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "provider_email", length = 255)
    private String providerEmail;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    public static SocialIdentityJpaEntity create(String accountId, String provider,
                                                  String providerUserId, String providerEmail) {
        SocialIdentityJpaEntity entity = new SocialIdentityJpaEntity();
        entity.accountId = accountId;
        entity.provider = provider;
        entity.providerUserId = providerUserId;
        entity.providerEmail = providerEmail;
        Instant now = Instant.now();
        entity.connectedAt = now;
        entity.lastUsedAt = now;
        return entity;
    }

    public void updateLastUsedAt() {
        this.lastUsedAt = Instant.now();
    }

    public void updateProviderEmail(String email) {
        this.providerEmail = email;
    }
}
