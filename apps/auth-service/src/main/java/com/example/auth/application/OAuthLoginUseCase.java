package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackCommand;
import com.example.auth.application.command.OAuthCallbackTxnCommand;
import com.example.auth.application.exception.*;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.result.AccountStatusLookupResult;
import com.example.auth.application.result.OAuthAuthorizeResult;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.SocialSignupResult;
import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.infrastructure.oauth.*;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaRepository;
import com.example.common.id.UuidV7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthLoginUseCase {

    private static final String STATE_KEY_PREFIX = "oauth:state:";
    // TTL aligned with specs/features/oauth-social-login.md: 10 minutes (TASK-BE-087).
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final OAuthProperties oAuthProperties;
    private final OAuthClientFactory oAuthClientFactory;
    private final StringRedisTemplate redisTemplate;
    private final OAuthLoginTransactionalStep oAuthLoginTransactionalStep;
    private final AccountServicePort accountServicePort;
    private final SocialIdentityJpaRepository socialIdentityJpaRepository;

    /**
     * Generates an authorization URL for the given OAuth provider.
     * Stores a random state in Redis with a 10-minute TTL for CSRF protection.
     */
    public OAuthAuthorizeResult authorize(String providerStr, String redirectUri) {
        OAuthProvider provider = parseProvider(providerStr);
        OAuthProperties.ProviderProperties props = getProviderProperties(provider);

        String effectiveRedirectUri = (redirectUri != null && !redirectUri.isBlank())
                ? redirectUri : props.getRedirectUri();

        validateRedirectUri(props, effectiveRedirectUri);

        String state = UuidV7.randomString();

        // Store state in Redis
        redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, provider.name(), STATE_TTL);

        String authorizationUrl = buildAuthorizationUrl(props, effectiveRedirectUri, state);

        return new OAuthAuthorizeResult(authorizationUrl, state);
    }

    /**
     * Processes the OAuth callback.
     *
     * <p>Orchestration: verifies state, performs the external provider token+userinfo
     * exchange (HTTP), then runs the pre-txn internal HTTP calls to account-service
     * ({@code socialSignup} on new-identity path, {@code getAccountStatus} always),
     * and only then hands the already-fetched data to
     * {@link OAuthLoginTransactionalStep#persistLogin} which owns the DB transaction.
     *
     * <p>TASK-BE-069 moved the external provider HTTP out of {@code @Transactional}.
     * TASK-BE-072 additionally moves the account-service internal HTTP calls out of
     * {@code @Transactional} — both external and internal HTTP previously held a
     * Hikari connection open during network I/O, reproducing the connection-pinning
     * pattern TASK-BE-069 intended to eliminate. This method is intentionally NOT
     * {@code @Transactional}.
     *
     * <p>Compensation: if the DB transaction fails after HTTP (provider + account-service)
     * succeeds, the user sees a login failure while the provider may have recorded an
     * authorization and account-service may have created a new account (socialSignup is
     * idempotent for the same email+provider, so retries are safe). The outbox rollback
     * ensures no downstream auth events are published on txn failure. No compensating
     * provider-side revoke is performed.
     *
     * <p>TOCTOU note: the identity existence check is now a non-txn DB read. The
     * transactional step still upserts the identity, so a concurrent insert between
     * the pre-read and the txn write cannot cause duplicate rows (unique key on
     * {@code (provider, provider_user_id)} is enforced at the DB).
     */
    public OAuthLoginResult callback(OAuthCallbackCommand command) {
        OAuthProvider provider = parseProvider(command.provider());
        SessionContext ctx = command.sessionContext();

        // Verify state from Redis (GETDEL for atomic check-and-delete).
        // Done outside txn — state check is an auth prerequisite, not a DB write.
        String stateKey = STATE_KEY_PREFIX + command.state();
        String storedProvider = redisTemplate.opsForValue().getAndDelete(stateKey);
        if (storedProvider == null) {
            throw new InvalidOAuthStateException();
        }

        OAuthProperties.ProviderProperties props = getProviderProperties(provider);
        String effectiveRedirectUri = (command.redirectUri() != null && !command.redirectUri().isBlank())
                ? command.redirectUri() : props.getRedirectUri();
        validateRedirectUri(props, effectiveRedirectUri);

        // External HTTP: token exchange + userinfo. OUTSIDE @Transactional (TASK-BE-069).
        OAuthClient client = oAuthClientFactory.getClient(provider);
        OAuthUserInfo userInfo;
        try {
            userInfo = client.exchangeCodeForUserInfo(command.code(), effectiveRedirectUri);
        } catch (OAuthProviderException e) {
            log.error("OAuth provider error for {}: {}", provider, e.getMessage());
            throw e;
        }

        // Validate email
        if (userInfo.email() == null || userInfo.email().isBlank()) {
            throw new OAuthEmailRequiredException();
        }

        // Non-txn DB read: does a local social identity already exist for this provider user?
        Optional<SocialIdentityJpaEntity> existingIdentity =
                socialIdentityJpaRepository.findByProviderAndProviderUserId(
                        provider.name(), userInfo.providerUserId());

        // Internal HTTP to account-service. OUTSIDE @Transactional (TASK-BE-072).
        String accountId;
        boolean isNewAccount;
        if (existingIdentity.isPresent()) {
            accountId = existingIdentity.get().getAccountId();
            isNewAccount = false;
        } else {
            SocialSignupResult signupResult = accountServicePort.socialSignup(
                    userInfo.email(), provider.name(), userInfo.providerUserId(), userInfo.name());
            accountId = signupResult.accountId();
            isNewAccount = signupResult.newAccount();
        }

        // Pre-fetched account status (TASK-BE-063 semantics: empty → account unavailable,
        // status guard is skipped and the rest of the flow proceeds — social signup path
        // would have created the account).
        Optional<String> accountStatus = accountServicePort.getAccountStatus(accountId)
                .map(AccountStatusLookupResult::accountStatus);

        // Hand off to the transactional bean — DB writes happen atomically here.
        return oAuthLoginTransactionalStep.persistLogin(
                new OAuthCallbackTxnCommand(provider, userInfo, ctx, accountId, isNewAccount, accountStatus));
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
            case MICROSOFT -> oAuthProperties.getMicrosoft();
        };
    }

    private void validateRedirectUri(OAuthProperties.ProviderProperties props, String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new InvalidOAuthRedirectUriException();
        }
        if (!props.resolveAllowedRedirectUris().contains(redirectUri)) {
            throw new InvalidOAuthRedirectUriException();
        }
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
}
