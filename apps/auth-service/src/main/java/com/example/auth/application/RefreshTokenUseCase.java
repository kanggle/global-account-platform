package com.example.auth.application;

import com.example.auth.application.command.RefreshTokenCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.SessionRevokedException;
import com.example.auth.application.exception.TokenExpiredException;
import com.example.auth.application.exception.TokenReuseDetectedException;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.RefreshTokenResult;
import com.example.auth.domain.repository.BulkInvalidationStore;
import com.example.auth.domain.repository.DeviceSessionRepository;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.repository.TokenBlacklist;
import com.example.auth.domain.session.DeviceSession;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import com.example.auth.domain.token.TokenReuseDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenUseCase {

    /** Session revoke reason code emitted on the session.revoked event when reuse is detected. */
    private static final String REVOKE_REASON_TOKEN_REUSE = "TOKEN_REUSE_DETECTED";
    private static final String ACTOR_TYPE_SYSTEM = "system";

    private final TokenGeneratorPort tokenGeneratorPort;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final TokenReuseDetector tokenReuseDetector;
    private final BulkInvalidationStore bulkInvalidationStore;
    private final AuthEventPublisher authEventPublisher;
    private final DeviceSessionRepository deviceSessionRepository;

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

        // Check bulk invalidation marker (UC-3 step 3, EF-3). Tokens issued before the marker
        // were invalidated en-masse (reuse detection, admin logout-all, account lock/delete).
        Optional<Instant> invalidatedAt = bulkInvalidationStore.getInvalidatedAt(accountId);
        if (invalidatedAt.isPresent()) {
            Instant tokenIat;
            try {
                tokenIat = tokenGeneratorPort.extractIssuedAt(command.refreshToken());
            } catch (Exception e) {
                // If we can't read iat, we can't prove the token is newer than the marker — deny.
                log.warn("Failed to extract iat for invalidate-all check, fail-closed: {}", e.getMessage());
                throw new SessionRevokedException();
            }
            if (tokenIat.isBefore(invalidatedAt.get())) {
                throw new SessionRevokedException();
            }
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

        // Rotation: inherit the existing device_id (D5: "Refresh rotation 시 새
        // refresh_tokens row는 동일한 device_id를 상속"). Touch the device_session
        // last_seen_at in the same transaction.
        String deviceId = existingToken.getDeviceId();
        if (deviceId != null) {
            deviceSessionRepository.findByDeviceId(deviceId).ifPresent(session -> {
                if (session.isActive()) {
                    session.touch(Instant.now(), ctx.ipAddress(), ctx.resolvedGeoCountry());
                    deviceSessionRepository.save(session);
                }
            });
        }

        TokenPair newTokenPair = tokenGeneratorPort.generateTokenPair(accountId, "user", deviceId);
        String newJti = tokenGeneratorPort.extractJti(newTokenPair.refreshToken());

        // Persist new refresh token with rotated_from pointing to old token, carrying
        // the same device_id and shadow-writing the deprecated device_fingerprint.
        Instant now = Instant.now();
        @SuppressWarnings("deprecation")
        String legacyFingerprint = existingToken.getDeviceFingerprint();
        RefreshToken newRefreshToken = RefreshToken.create(
                newJti, accountId, now,
                now.plusSeconds(tokenGeneratorPort.refreshTokenTtlSeconds()),
                jti, legacyFingerprint, deviceId
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

        // Capture active jtis BEFORE the bulk update so the session.revoked event can list every
        // jti that transitioned from active to revoked.
        List<String> activeJtis = refreshTokenRepository.findActiveJtisByAccountId(accountId);

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
                activeJtis,
                REVOKE_REASON_TOKEN_REUSE,
                ACTOR_TYPE_SYSTEM,
                null,
                reuseAttemptAt,
                revokedCount
        );
    }
}
