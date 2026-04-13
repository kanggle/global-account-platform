package com.example.auth.application.port;

import com.example.auth.domain.token.TokenPair;

import java.time.Instant;

/**
 * Port interface for generating JWT token pairs.
 * Implementation lives in infrastructure/jwt/.
 */
public interface TokenGeneratorPort {

    /**
     * Generates a new access + refresh token pair without an attached device session.
     * Equivalent to {@link #generateTokenPair(String, String, String)} with {@code deviceId = null}.
     */
    default TokenPair generateTokenPair(String accountId, String scope) {
        return generateTokenPair(accountId, scope, null);
    }

    /**
     * Generates a new access + refresh token pair.
     *
     * @param accountId the account ID to embed as {@code sub}
     * @param scope     the {@code scope} claim (e.g. "user", "admin")
     * @param deviceId  the {@code device_id} claim (opaque UUID v7 of the device session);
     *                  may be {@code null} for legacy / pre-session-integration callers
     * @return token pair with access token, refresh token, and access TTL
     */
    TokenPair generateTokenPair(String accountId, String scope, String deviceId);

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

    /**
     * Extracts the issued-at (iat) instant from a refresh token string.
     */
    Instant extractIssuedAt(String refreshToken);
}
