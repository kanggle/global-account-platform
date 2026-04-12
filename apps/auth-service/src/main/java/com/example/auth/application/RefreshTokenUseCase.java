package com.example.auth.application;

import com.example.auth.application.command.RefreshTokenCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.SessionRevokedException;
import com.example.auth.application.exception.TokenExpiredException;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.RefreshTokenResult;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.repository.TokenBlacklist;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenUseCase {

    private final TokenGeneratorPort tokenGeneratorPort;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final AuthEventPublisher authEventPublisher;

    @Transactional
    public RefreshTokenResult execute(RefreshTokenCommand command) {
        SessionContext ctx = command.sessionContext();

        // Extract JTI and account ID from the refresh token
        String jti;
        String accountId;
        try {
            jti = tokenGeneratorPort.extractJti(command.refreshToken());
            accountId = tokenGeneratorPort.extractAccountId(command.refreshToken());
        } catch (Exception e) {
            log.warn("Failed to parse refresh token: {}", e.getMessage());
            throw new TokenExpiredException();
        }

        // Check blacklist (fail-closed: if Redis is down, deny refresh)
        if (tokenBlacklist.isBlacklisted(jti)) {
            throw new SessionRevokedException();
        }

        // Look up the refresh token in DB
        RefreshToken existingToken = refreshTokenRepository.findByJti(jti)
                .orElseThrow(TokenExpiredException::new);

        // Check if revoked
        if (existingToken.isRevoked()) {
            throw new SessionRevokedException();
        }

        // Check if expired
        if (existingToken.isExpired()) {
            throw new TokenExpiredException();
        }

        // Check for reuse: if this token has already been rotated (a child token exists),
        // this is a reuse attempt. Revoke all tokens and publish security event.
        if (refreshTokenRepository.existsByRotatedFrom(jti)) {
            log.warn("Refresh token reuse detected for account={}, jti={}", accountId, jti);

            // Find the child token to determine originalRotationAt
            Instant originalRotationAt = refreshTokenRepository.findByRotatedFrom(jti)
                    .map(RefreshToken::getIssuedAt)
                    .orElse(null);

            // Revoke all tokens for this account
            int revokedCount = refreshTokenRepository.revokeAllByAccountId(accountId);

            // Publish reuse detection event (within @Transactional boundary for outbox)
            Instant reuseAttemptAt = Instant.now();
            authEventPublisher.publishTokenReuseDetected(
                    accountId,
                    jti,
                    originalRotationAt,
                    reuseAttemptAt,
                    ctx.ipMasked(),
                    ctx.deviceFingerprint(),
                    true,
                    revokedCount
            );

            throw new SessionRevokedException();
        }

        // Rotation: generate new token pair
        TokenPair newTokenPair = tokenGeneratorPort.generateTokenPair(accountId, "user");
        String newJti = tokenGeneratorPort.extractJti(newTokenPair.refreshToken());

        // Persist new refresh token with rotated_from pointing to old token
        Instant now = Instant.now();
        RefreshToken newRefreshToken = RefreshToken.create(
                newJti, accountId, now,
                now.plusSeconds(tokenGeneratorPort.refreshTokenTtlSeconds()),
                jti, existingToken.getDeviceFingerprint()
        );
        refreshTokenRepository.save(newRefreshToken);

        // Blacklist the old refresh token
        long remainingTtl = existingToken.getExpiresAt().getEpochSecond() - now.getEpochSecond();
        if (remainingTtl > 0) {
            tokenBlacklist.blacklist(jti, remainingTtl);
        }

        // Publish event
        authEventPublisher.publishTokenRefreshed(accountId, jti, newJti, ctx);

        return RefreshTokenResult.of(
                newTokenPair.accessToken(),
                newTokenPair.refreshToken(),
                newTokenPair.expiresIn()
        );
    }
}
