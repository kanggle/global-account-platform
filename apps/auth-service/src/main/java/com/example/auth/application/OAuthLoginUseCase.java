package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackCommand;
import com.example.auth.application.command.OAuthCallbackTxnCommand;
import com.example.auth.application.exception.*;
import com.example.auth.application.result.OAuthAuthorizeResult;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.infrastructure.oauth.*;
import com.example.common.id.UuidV7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthLoginUseCase {

    private static final String STATE_KEY_PREFIX = "oauth:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(5);

    private final OAuthProperties oAuthProperties;
    private final OAuthClientFactory oAuthClientFactory;
    private final StringRedisTemplate redisTemplate;
    private final OAuthLoginTransactionalStep oAuthLoginTransactionalStep;

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
     * Processes the OAuth callback.
     *
     * <p>Orchestration: verifies state, performs the external provider token+userinfo
     * exchange (HTTP), then hands the already-fetched data to
     * {@link OAuthLoginTransactionalStep#persistLogin} which owns the DB transaction.
     *
     * <p>TASK-BE-069: External HTTP calls MUST NOT happen inside {@code @Transactional}
     * — they block a Hikari connection for the duration of the provider round-trip and
     * caused connection-pool exhaustion in CI integration tests. This method is
     * intentionally NOT {@code @Transactional}.
     *
     * <p>Compensation: if the DB transaction fails after the provider HTTP succeeds,
     * the user sees a login failure while the provider may have recorded an
     * authorization. This matches prior behaviour (outbox write also rolls back so no
     * downstream event is published). No compensating provider-side revoke is
     * performed.
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

        // External HTTP: token exchange + userinfo. OUTSIDE @Transactional (TASK-BE-069).
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

        // Hand off to the transactional bean — DB writes happen atomically here.
        return oAuthLoginTransactionalStep.persistLogin(
                new OAuthCallbackTxnCommand(provider, userInfo, ctx));
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
