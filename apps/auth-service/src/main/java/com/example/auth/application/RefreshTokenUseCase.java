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
import com.example.auth.domain.session.RevokeReason;
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

    private static final String ACTOR_TYPE_SYSTEM = "SYSTEM";

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

        // Look up the refresh token in DB. Reuse detection must run BEFORE any revoked/
        // blacklisted/invalidate-all short-circuit so a replay of a rotated token still
        // triggers the incident-response path (TASK-BE-062 §B — security-first ordering).
        RefreshToken existingToken = refreshTokenRepository.findByJti(jti)
                .orElseThrow(TokenExpiredException::new);

        // Check for reuse FIRST. If a child token with rotated_from=jti exists, this presentation
        // is a replay regardless of the token's own revoked flag, the Redis blacklist entry, or
        // the bulk-invalidation marker. Missing any of those signals would silently downgrade a
        // security incident to a plain "session revoked" 401.
        if (tokenReuseDetector.isReuse(existingToken)) {
            handleReuseDetected(existingToken, jti, accountId, ctx);
            // handleReuseDetected always throws
            throw new TokenReuseDetectedException();
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
     * Handles a detected refresh-token reuse: bulk-revokes the account's refresh tokens
     * and device sessions, sets the Redis invalidate-all marker, emits
     * {@code auth.token.reuse.detected} and one {@code auth.session.revoked} event per
     * affected device, and throws {@link TokenReuseDetectedException}.
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

        // Snapshot active device_sessions + their active jtis BEFORE revoking so we can emit
        // one event per device with the jtis that actually transitioned active -> revoked.
        List<DeviceSession> activeSessions = deviceSessionRepository.findActiveByAccountId(accountId);
        java.util.Map<String, List<String>> jtisByDevice = new java.util.LinkedHashMap<>();
        for (DeviceSession session : activeSessions) {
            jtisByDevice.put(session.getDeviceId(),
                    refreshTokenRepository.findActiveJtisByDeviceId(session.getDeviceId()));
        }

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

        // Cascade: mark each device_session revoked + emit per-device auth.session.revoked.
        // Idempotent — already-revoked sessions are skipped.
        for (DeviceSession session : activeSessions) {
            if (session.isRevoked()) {
                continue;
            }
            List<String> deviceJtis = jtisByDevice.getOrDefault(session.getDeviceId(), List.of());
            session.revoke(reuseAttemptAt, RevokeReason.TOKEN_REUSE);
            deviceSessionRepository.save(session);
            authEventPublisher.publishAuthSessionRevoked(
                    accountId,
                    session.getDeviceId(),
                    RevokeReason.TOKEN_REUSE.name(),
                    deviceJtis,
                    reuseAttemptAt,
                    ACTOR_TYPE_SYSTEM,
                    null
            );
        }
    }
}
