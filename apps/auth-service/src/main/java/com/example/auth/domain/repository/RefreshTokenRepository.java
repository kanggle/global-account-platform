package com.example.auth.domain.repository;

import com.example.auth.domain.token.RefreshToken;

import java.util.Optional;

/**
 * Port interface for refresh token persistence.
 */
public interface RefreshTokenRepository {

    Optional<RefreshToken> findByJti(String jti);

    RefreshToken save(RefreshToken refreshToken);

    boolean existsByRotatedFrom(String jti);

    /**
     * Revokes all active refresh tokens for the given account.
     * @return the number of tokens revoked
     */
    int revokeAllByAccountId(String accountId);

    /**
     * Finds the child token that was rotated from the given JTI.
     */
    java.util.Optional<RefreshToken> findByRotatedFrom(String jti);
}
