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

    void revokeAllByAccountId(String accountId);
}
