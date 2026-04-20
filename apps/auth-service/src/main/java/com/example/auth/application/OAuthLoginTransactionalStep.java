package com.example.auth.application;

import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.RegisterDeviceSessionResult;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import com.example.auth.infrastructure.oauth.OAuthUserInfo;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence-phase step of the OAuth login callback. Owns the database
 * writes that must be atomic: social_identities upsert, device session
 * registration, refresh token persistence, and outbox event emission.
 *
 * <p>TASK-BE-066: extracted from {@code OAuthLoginUseCase#callback} so that
 * the {@code @Transactional} boundary does <b>not</b> wrap the external HTTP
 * calls to the OAuth providers or to account-service. The caller performs
 * those calls first and hands the resolved identity into this step.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthLoginTransactionalStep {

    private final SocialIdentityJpaRepository socialIdentityJpaRepository;
    private final RegisterOrUpdateDeviceSessionUseCase registerOrUpdateDeviceSessionUseCase;
    private final TokenGeneratorPort tokenGeneratorPort;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthEventPublisher authEventPublisher;

    /**
     * Persists the outcome of an OAuth callback atomically.
     *
     * <p>The caller must already have:</p>
     * <ul>
     *   <li>validated the state token</li>
     *   <li>exchanged the OAuth code for {@link OAuthUserInfo}</li>
     *   <li>resolved the {@code accountId} (via account-service social
     *       signup when the social identity did not yet exist)</li>
     *   <li>verified the account status (ACTIVE)</li>
     * </ul>
     *
     * <p>All database writes below run in a single transaction so the
     * outbox event is only emitted if persistence succeeds, preserving
     * outbox-first semantics ([rules/traits/transactional.md] T3).</p>
     */
    @Transactional
    public OAuthLoginResult persist(OAuthCallbackPersistCommand command) {
        OAuthUserInfo userInfo = command.userInfo();
        OAuthProvider provider = userInfo.provider();
        SessionContext ctx = command.sessionContext();
        String accountId = command.accountId();
        boolean isNewAccount = command.newAccount();

        // Social identity upsert — re-fetch inside the transaction to avoid a
        // race with a concurrent callback for the same (provider, subject).
        Optional<SocialIdentityJpaEntity> existing = socialIdentityJpaRepository
                .findByProviderAndProviderUserId(provider.name(), userInfo.providerUserId());

        if (existing.isPresent()) {
            SocialIdentityJpaEntity identity = existing.get();
            identity.updateLastUsedAt();
            if (userInfo.email() != null && !userInfo.email().equals(identity.getProviderEmail())) {
                identity.updateProviderEmail(userInfo.email());
            }
            socialIdentityJpaRepository.save(identity);
        } else {
            SocialIdentityJpaEntity newIdentity = SocialIdentityJpaEntity.create(
                    accountId, provider.name(), userInfo.providerUserId(), userInfo.email());
            socialIdentityJpaRepository.save(newIdentity);
        }

        // Register/update device session (requires active transaction — MANDATORY).
        RegisterDeviceSessionResult sessionResult =
                registerOrUpdateDeviceSessionUseCase.execute(accountId, ctx);
        String deviceId = sessionResult.deviceId();

        // Issue tokens (pure, in-memory).
        TokenPair tokenPair = tokenGeneratorPort.generateTokenPair(accountId, "user", deviceId);

        // Persist refresh token.
        String refreshJti = tokenGeneratorPort.extractJti(tokenPair.refreshToken());
        Instant now = Instant.now();
        RefreshToken refreshTokenEntity = RefreshToken.create(
                refreshJti, accountId, now,
                now.plusSeconds(tokenGeneratorPort.refreshTokenTtlSeconds()),
                null,
                ctx.deviceFingerprint(),
                deviceId
        );
        refreshTokenRepository.save(refreshTokenEntity);

        // Outbox events (within the same transaction).
        authEventPublisher.publishLoginSucceeded(accountId, refreshJti, ctx,
                deviceId, sessionResult.newSession(), provider.loginMethod());

        if (sessionResult.newSession()) {
            authEventPublisher.publishAuthSessionCreated(
                    accountId, deviceId, refreshJti,
                    LoginUseCase.fingerprintHash(ctx.deviceFingerprint()),
                    ctx.userAgentFamily(),
                    ctx.ipMasked(),
                    ctx.resolvedGeoCountry(),
                    now,
                    sessionResult.evictedDeviceIds());
        }

        return new OAuthLoginResult(
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                tokenPair.expiresIn(),
                tokenGeneratorPort.refreshTokenTtlSeconds(),
                isNewAccount
        );
    }
}
