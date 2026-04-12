package com.example.auth.application;

import com.example.auth.application.command.RefreshTokenCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.SessionRevokedException;
import com.example.auth.application.exception.TokenExpiredException;
import com.example.auth.application.exception.TokenReuseDetectedException;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.RefreshTokenResult;
import com.example.auth.domain.repository.BulkInvalidationStore;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.repository.TokenBlacklist;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import com.example.auth.domain.token.TokenReuseDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenUseCase {

    /** Session revoke reason code emitted on the session.revoked event when reuse is detected. */
    private static final String REVOKE_REASON_TOKEN_REUSE = "TOKEN_REUSE_DETECTED";
    private static final String ACTOR_TYPE_SYSTEM = "SYSTEM";

    private final TokenGeneratorPort tokenGeneratorPort;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final TokenReuseDetector tokenReuseDetector;
    private final BulkInvalidationStore bulkInvalidationStore;
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

        // Check for reuse BEFORE inspecting revoked/expired flags — reuse of a previously-rotated
        // (and therefore revoked) token must still trigger the full incident-response path.
        if (tokenReuseDetector.isReuse(existingToken)) {
            handleReuseDetected(existingToken, jti, accountId, ctx);
            // handleReuseDetected always throws
            throw new TokenReuseDetectedException();
        }

        // Check if revoked
        if (existingToken.isRevoked()) {
            throw new SessionRevokedException();
        }

        // Check if expired
        if (existingToken.isExpired()) {
            throw new TokenExpiredException();
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

    /**
     * Handles a detected refresh-token reuse: bulk-revokes the account, sets the Redis
     * invalidate-all marker, emits {@code auth.token.reuse.detected} and {@code session.revoked}
     * events, and throws {@link TokenReuseDetectedException}.
     *
     * <p>All work runs inside the caller's {@code @Transactional} boundary so that DB revokes and
     * outbox writes commit atomically.
     */
    private void handleReuseDetected(RefreshToken existingToken, String jti, String accountId,
                                     SessionContext ctx) {
        log.warn("Refresh token reuse detected for account={}, jti={}", accountId, jti);

        // Determine when the legitimate rotation happened (for forensic context).
        Instant originalRotationAt = refreshTokenRepository.findByRotatedFrom(jti)
                .map(RefreshToken::getIssuedAt)
                .orElse(null);

        // Idempotence: if the owner token is already revoked, skip re-publishing events but
        // still enforce the TokenReuseDetectedException response to the caller.
        boolean alreadyRevoked = existingToken.isRevoked();

        // Revoke every active refresh token for this account (DB authoritative defence).
        int revokedCount = refreshTokenRepository.revokeAllByAccountId(accountId);

        // Best-effort Redis bulk-invalidation marker with TTL = max refresh lifetime.
        bulkInvalidationStore.invalidateAll(accountId, tokenGeneratorPort.refreshTokenTtlSeconds());

        if (alreadyRevoked && revokedCount == 0) {
            log.info("Token reuse on an already-revoked account, skipping duplicate event emission: account={}",
                    accountId);
            return;
        }

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
        authEventPublisher.publishSessionRevoked(
                accountId,
                Collections.singletonList(jti),
                REVOKE_REASON_TOKEN_REUSE,
                ACTOR_TYPE_SYSTEM,
                null,
                reuseAttemptAt,
                revokedCount
        );
    }
}
