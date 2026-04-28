package com.example.auth.infrastructure.jwt;

import com.example.auth.application.exception.TokenParseException;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.domain.token.TokenPair;
import com.gap.security.jwt.JwtSigner;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenGenerator implements TokenGeneratorPort {

    private final JwtSigner jwtSigner;
    private final PublicKey publicKey;
    private final String issuer;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;

    public JwtTokenGenerator(
            JwtSigner jwtSigner,
            PublicKey publicKey,
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.jwt.access-token-ttl-seconds}") long accessTokenTtlSeconds,
            @Value("${auth.jwt.refresh-token-ttl-seconds}") long refreshTokenTtlSeconds) {
        this.jwtSigner = jwtSigner;
        this.publicKey = publicKey;
        this.issuer = issuer;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    @Override
    public TokenPair generateTokenPair(String accountId, String scope, String deviceId) {
        Instant now = Instant.now();
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();

        // Build access token claims
        Map<String, Object> accessClaims = new LinkedHashMap<>();
        accessClaims.put("sub", accountId);
        accessClaims.put("iss", issuer);
        accessClaims.put("iat", now);
        accessClaims.put("exp", now.plusSeconds(accessTokenTtlSeconds));
        accessClaims.put("jti", accessJti);
        accessClaims.put("scope", scope);
        if (deviceId != null) {
            // device_id claim: opaque UUID v7 of the device session that owns this access token.
            // See specs/contracts/http/auth-api.md "Access Token claims" + device-session.md D5.
            accessClaims.put("device_id", deviceId);
        }

        String accessToken = jwtSigner.sign(accessClaims);

        // Build refresh token claims
        Map<String, Object> refreshClaims = new LinkedHashMap<>();
        refreshClaims.put("sub", accountId);
        refreshClaims.put("jti", refreshJti);
        refreshClaims.put("iat", now);
        refreshClaims.put("exp", now.plusSeconds(refreshTokenTtlSeconds));
        refreshClaims.put("type", "refresh");

        String refreshToken = jwtSigner.sign(refreshClaims);

        return new TokenPair(accessToken, refreshToken, accessTokenTtlSeconds);
    }

    @Override
    public long accessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    @Override
    public long refreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    @Override
    public String extractJti(String refreshToken) {
        return parseClaims(refreshToken).getId();
    }

    @Override
    public String extractAccountId(String refreshToken) {
        return parseClaims(refreshToken).getSubject();
    }

    @Override
    public Instant extractIssuedAt(String refreshToken) {
        java.util.Date iat = parseClaims(refreshToken).getIssuedAt();
        if (iat == null) {
            throw new TokenParseException("JWT missing iat claim");
        }
        return iat.toInstant();
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenParseException(e.getMessage(), e);
        }
    }
}
