package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.*;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.OAuthAuthorizeResult;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.RegisterDeviceSessionResult;
import com.example.auth.application.result.SocialSignupResult;
import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import com.example.auth.infrastructure.oauth.*;
import com.example.common.id.UuidV7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthLoginUseCase {

    private static final String STATE_KEY_PREFIX = "oauth:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(5);

    private final OAuthProperties oAuthProperties;
    private final OAuthClientFactory oAuthClientFactory;
    private final AccountServicePort accountServicePort;
    private final TokenGeneratorPort tokenGeneratorPort;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthEventPublisher authEventPublisher;
    private final RegisterOrUpdateDeviceSessionUseCase registerOrUpdateDeviceSessionUseCase;
    private final StringRedisTemplate redisTemplate;
    private final com.example.auth.infrastructure.persistence.SocialIdentityJpaRepository socialIdentityJpaRepository;

    /**
     * Generates an authorization URL for the given OAuth provider.
     * Stores a random state in Redis with a 5-minute TTL for CSRF protection.
     */
    public OAuthAuthorizeResult authorize(String providerStr, String redirectUri) {
        OAuthProvider provider = parseProvider(providerStr);
        OAuthProperties.ProviderProperties props = getProviderProperties(provider);

        String state = UuidV7.randomString();

        // Store state in Redis
        redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, provider.name(), STATE_TTL);

        String effectiveRedirectUri = (redirectUri != null && !redirectUri.isBlank())
                ? redirectUri : props.getRedirectUri();

        String authorizationUrl = buildAuthorizationUrl(props, effectiveRedirectUri, state);

        return new OAuthAuthorizeResult(authorizationUrl, state);
    }

    /**
     * Processes the OAuth callback: verifies state, exchanges code for user info,
     * finds or creates the account, issues JWT tokens, creates device session,
     * and publishes events.
     */
    @Transactional
    public OAuthLoginResult callback(OAuthCallbackCommand command) {
        OAuthProvider provider = parseProvider(command.provider());
        SessionContext ctx = command.sessionContext();

        // Verify state from Redis (GETDEL for atomic check-and-delete)
        String stateKey = STATE_KEY_PREFIX + command.state();
        String storedProvider = redisTemplate.opsForValue().getAndDelete(stateKey);
        if (storedProvider == null) {
            throw new InvalidOAuthStateException();
        }

        // Exchange code for user info
        OAuthClient client = oAuthClientFactory.getClient(provider);
        OAuthUserInfo userInfo;
        try {
            String effectiveRedirectUri = (command.redirectUri() != null && !command.redirectUri().isBlank())
                    ? command.redirectUri() : getProviderProperties(provider).getRedirectUri();
            userInfo = client.exchangeCodeForUserInfo(command.code(), effectiveRedirectUri);
        } catch (OAuthProviderException e) {
            log.error("OAuth provider error for {}: {}", provider, e.getMessage());
            throw e;
        }

        // Validate email
        if (userInfo.email() == null || userInfo.email().isBlank()) {
            throw new OAuthEmailRequiredException();
        }

        // Lookup social identity
        boolean isNewAccount = false;
        String accountId;
        Optional<com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity> existingIdentity =
                socialIdentityJpaRepository.findByProviderAndProviderUserId(
                        provider.name(), userInfo.providerUserId());

        if (existingIdentity.isPresent()) {
            // Existing social identity — update lastUsedAt and providerEmail if changed
            var identity = existingIdentity.get();
            accountId = identity.getAccountId();
            identity.updateLastUsedAt();
            if (userInfo.email() != null && !userInfo.email().equals(identity.getProviderEmail())) {
                identity.updateProviderEmail(userInfo.email());
            }
            socialIdentityJpaRepository.save(identity);
        } else {
            // No social identity found — call account-service for social signup
            SocialSignupResult signupResult = accountServicePort.socialSignup(
                    userInfo.email(), provider.name(), userInfo.providerUserId(), userInfo.name());
            accountId = signupResult.accountId();
            isNewAccount = signupResult.newAccount();

            // Create social identity record
            var newIdentity = com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity.create(
                    accountId, provider.name(), userInfo.providerUserId(), userInfo.email());
            socialIdentityJpaRepository.save(newIdentity);
        }

        // Check account status via credential lookup
        var credentialOpt = accountServicePort.lookupCredentialsByEmail(userInfo.email());
        if (credentialOpt.isPresent()) {
            String status = credentialOpt.get().accountStatus();
            checkAccountStatus(status);
        }

        // Register/update device session
        RegisterDeviceSessionResult sessionResult =
                registerOrUpdateDeviceSessionUseCase.execute(accountId, ctx);
        String deviceId = sessionResult.deviceId();

        // Issue JWT tokens
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

    private OAuthProvider parseProvider(String providerStr) {
        try {
            return OAuthProvider.from(providerStr);
        } catch (IllegalArgumentException e) {
            throw new UnsupportedProviderException(providerStr);
        }
    }

    private OAuthProperties.ProviderProperties getProviderProperties(OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> oAuthProperties.getGoogle();
            case KAKAO -> oAuthProperties.getKakao();
        };
    }

    private String buildAuthorizationUrl(OAuthProperties.ProviderProperties props,
                                          String redirectUri, String state) {
        return props.getAuthUri()
                + "?client_id=" + encode(props.getClientId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=" + encode(props.getScopes().replace(",", " "))
                + "&state=" + encode(state);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
