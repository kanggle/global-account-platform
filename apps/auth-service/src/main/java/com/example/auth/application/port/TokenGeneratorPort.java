package com.example.auth.application.port;

import com.example.auth.domain.token.TokenPair;

/**
 * Port interface for generating JWT token pairs.
 * Implementation lives in infrastructure/jwt/.
 */
public interface TokenGeneratorPort {

    /**
     * Generates a new access + refresh token pair.
     *
     * @param accountId the account ID to embed as 'sub' claim
     * @param scope     the scope claim (e.g. "user", "admin")
     * @return token pair with access token, refresh token JTI, and TTL
     */
    TokenPair generateTokenPair(String accountId, String scope);

    /**
     * Returns the access token TTL in seconds.
     */
    long accessTokenTtlSeconds();

    /**
     * Returns the refresh token TTL in seconds.
     */
    long refreshTokenTtlSeconds();

    /**
     * Extracts the JTI from a refresh token string.
     */
    String extractJti(String refreshToken);

    /**
     * Extracts the account ID (sub) from a refresh token string.
     */
    String extractAccountId(String refreshToken);
}
