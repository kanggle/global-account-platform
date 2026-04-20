package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackTxnCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.AccountLockedException;
import com.example.auth.application.exception.AccountStatusException;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.RegisterDeviceSessionResult;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Transactional boundary for the OAuth callback flow.
 *
 * <p>All DB writes (social identity upsert, refresh token persist, outbox event writes)
 * happen inside a single {@code @Transactional} method on this bean. The enclosing
 * {@link OAuthLoginUseCase} performs ALL HTTP work BEFORE calling
 * {@link #persistLogin(OAuthCallbackTxnCommand)}:
 * <ul>
 *   <li>external provider token+userinfo exchange (TASK-BE-069)</li>
 *   <li>internal account-service {@code socialSignup} and {@code getAccountStatus}
 *       (TASK-BE-072)</li>
 * </ul>
 * so that no HTTP call holds a DB connection (resolves Hikari connection pinning
 * observed in TASK-BE-062 #18 CI runs).
 *
 * <p>This bean therefore has NO dependency on {@code AccountServicePort} — all
 * account-service calls live in the orchestrator.
 *
 * <p>Compensation note: HTTP (external + internal) completes before this DB txn; if
 * the txn fails to commit the user sees a login failure while the provider may
 * already have issued tokens and account-service may have created an account
 * ({@code socialSignup} is idempotent). Outbox rollback already meant downstream
 * events were never published on txn failure. No compensating provider-side revoke
 * is performed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthLoginTransactionalStep {

    private final TokenGeneratorPort tokenGeneratorPort;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthEventPublisher authEventPublisher;
    private final RegisterOrUpdateDeviceSessionUseCase registerOrUpdateDeviceSessionUseCase;
    private final SocialIdentityJpaRepository socialIdentityJpaRepository;

    @Transactional
    public OAuthLoginResult persistLogin(OAuthCallbackTxnCommand command) {
        OAuthProvider provider = command.provider();
        OAuthUserInfo userInfo = command.userInfo();
        SessionContext ctx = command.sessionContext();
        String accountId = command.accountId();
        boolean isNewAccount = command.isNewAccount();

        // Upsert local social identity. Orchestrator already resolved accountId via
        // either an existing identity (DB read) or accountServicePort.socialSignup
        // (HTTP) — both performed OUTSIDE this @Transactional boundary (TASK-BE-072).
        Optional<SocialIdentityJpaEntity> existingIdentity =
                socialIdentityJpaRepository.findByProviderAndProviderUserId(
                        provider.name(), userInfo.providerUserId());

        if (existingIdentity.isPresent()) {
            var identity = existingIdentity.get();
            identity.updateLastUsedAt();
            if (userInfo.email() != null && !userInfo.email().equals(identity.getProviderEmail())) {
                identity.updateProviderEmail(userInfo.email());
            }
            socialIdentityJpaRepository.save(identity);
        } else {
            var newIdentity = SocialIdentityJpaEntity.create(
                    accountId, provider.name(), userInfo.providerUserId(), userInfo.email());
            socialIdentityJpaRepository.save(newIdentity);
        }

        // Account status check against pre-fetched value (no HTTP here).
        // Empty status = account-service returned no status → treat as unavailable
        // and skip the guard (existing TASK-BE-063 behaviour — the rest of the flow
        // proceeds because the social-signup path already created the account).
        command.accountStatus().ifPresent(this::checkAccountStatus);

        // Register/update device session (propagation MANDATORY — joins this txn)
        RegisterDeviceSessionResult sessionResult =
                registerOrUpdateDeviceSessionUseCase.execute(accountId, ctx);
        String deviceId = sessionResult.deviceId();

        // Issue JWT tokens (no DB/HTTP — pure crypto)
        TokenPair tokenPair = tokenGeneratorPort.generateTokenPair(accountId, "user", deviceId);

        // Persist refresh token
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

        // Publish login succeeded event with loginMethod
        authEventPublisher.publishLoginSucceeded(accountId, refreshJti, ctx,
                deviceId, sessionResult.newSession(), provider.loginMethod());

        // Publish session created event if new session
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

    private void checkAccountStatus(String status) {
        switch (status) {
            case "ACTIVE" -> { /* proceed */ }
            case "LOCKED" -> throw new AccountLockedException();
            case "DORMANT" -> throw new AccountStatusException("DORMANT", "ACCOUNT_DORMANT");
            case "DELETED" -> throw new AccountStatusException("DELETED", "ACCOUNT_DELETED");
            default -> throw new AccountStatusException(status, "ACCOUNT_STATUS_UNKNOWN");
        }
    }
}
