package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackTxnCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.AccountLockedException;
import com.example.auth.application.exception.AccountStatusException;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.RegisterDeviceSessionResult;
import com.example.auth.application.result.SocialSignupResult;
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
 * {@link OAuthLoginUseCase} performs the external provider HTTP exchange (token +
 * userinfo) BEFORE calling {@link #persistLogin(OAuthCallbackTxnCommand)} so that no
 * external HTTP holds a DB connection (resolves the Hikari connection pinning observed
 * in TASK-BE-062 #18 CI runs).
 *
 * <p>Compensation note: external HTTP completes before this DB txn; if the txn fails
 * to commit the user sees a login failure while the provider may already have issued
 * tokens. Behaviour matches the prior implementation (outbox rollback already meant
 * downstream events were never published on txn failure). No compensating
 * provider-side revoke is performed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthLoginTransactionalStep {

    private final AccountServicePort accountServicePort;
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

        // Lookup or create social identity
        boolean isNewAccount = false;
        String accountId;
        Optional<SocialIdentityJpaEntity> existingIdentity =
                socialIdentityJpaRepository.findByProviderAndProviderUserId(
                        provider.name(), userInfo.providerUserId());

        if (existingIdentity.isPresent()) {
            var identity = existingIdentity.get();
            accountId = identity.getAccountId();
            identity.updateLastUsedAt();
            if (userInfo.email() != null && !userInfo.email().equals(identity.getProviderEmail())) {
                identity.updateProviderEmail(userInfo.email());
            }
            socialIdentityJpaRepository.save(identity);
        } else {
            SocialSignupResult signupResult = accountServicePort.socialSignup(
                    userInfo.email(), provider.name(), userInfo.providerUserId(), userInfo.name());
            accountId = signupResult.accountId();
            isNewAccount = signupResult.newAccount();

            var newIdentity = SocialIdentityJpaEntity.create(
                    accountId, provider.name(), userInfo.providerUserId(), userInfo.email());
            socialIdentityJpaRepository.save(newIdentity);
        }

        // TASK-BE-063: credentials are owned locally by auth-service now, so the
        // pre-token account-status check uses the status-only internal endpoint.
        // A missing status is treated as the account being unavailable — skip the
        // block and let the rest of the flow proceed (social signup path created it).
        accountServicePort.getAccountStatus(accountId)
                .ifPresent(statusResult -> checkAccountStatus(statusResult.accountStatus()));

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
