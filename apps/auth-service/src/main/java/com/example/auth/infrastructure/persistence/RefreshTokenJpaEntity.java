package com.example.auth.infrastructure.persistence;

import com.example.auth.domain.token.RefreshToken;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "jti", nullable = false, unique = true, length = 36)
    private String jti;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "rotated_from", length = 36)
    private String rotatedFrom;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    public RefreshToken toDomain() {
        return new RefreshToken(id, jti, accountId, issuedAt, expiresAt, rotatedFrom, revoked, deviceFingerprint);
    }

    public static RefreshTokenJpaEntity fromDomain(RefreshToken token) {
        RefreshTokenJpaEntity entity = new RefreshTokenJpaEntity();
        entity.id = token.getId();
        entity.jti = token.getJti();
        entity.accountId = token.getAccountId();
        entity.issuedAt = token.getIssuedAt();
        entity.expiresAt = token.getExpiresAt();
        entity.rotatedFrom = token.getRotatedFrom();
        entity.revoked = token.isRevoked();
        entity.deviceFingerprint = token.getDeviceFingerprint();
        return entity;
    }
}
