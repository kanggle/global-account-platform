package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackCommand;
import com.example.auth.application.exception.*;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.result.OAuthAuthorizeResult;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.SocialSignupResult;
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
    private final StringRedisTemplate redisTemplate;
    private final com.example.auth.infrastructure.persistence.SocialIdentityJpaRepository socialIdentityJpaRepository;
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
     * Orchestrates the OAuth callback end-to-end.
     *
     * <p>TASK-BE-066: the {@code @Transactional} boundary has been narrowed
     * to only the persistence phase in
     * {@link OAuthLoginTransactionalStep#persist}. All external HTTP calls —
     * OAuth provider token exchange and account-service social-signup /
     * status lookup — run <b>outside</b> any transaction so Hikari
     * connections are not pinned across blocking network I/O.</p>
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Verify state via Redis {@code GETDEL}.</li>
     *   <li>Exchange the OAuth code for user info (external HTTP).</li>
     *   <li>Validate email presence.</li>
     *   <li>Look up existing social identity (auto-commit read).</li>
     *   <li>If none, call account-service {@code socialSignup} (external HTTP).</li>
     *   <li>Call account-service {@code getAccountStatus} (external HTTP)
     *       and enforce the ACTIVE/LOCKED/… contract.</li>
     *   <li>Hand the resolved inputs to
     *       {@link OAuthLoginTransactionalStep#persist} to atomically write
     *       social_identity / device session / refresh token / outbox.</li>
     * </ol>
     */
    public OAuthLoginResult callback(OAuthCallbackCommand command) {
        OAuthProvider provider = parseProvider(command.provider());
        SessionContext ctx = command.sessionContext();

        // 1. Verify state (Redis GETDEL — no DB transaction needed).
        String stateKey = STATE_KEY_PREFIX + command.state();
        String storedProvider = redisTemplate.opsForValue().getAndDelete(stateKey);
        if (storedProvider == null) {
            throw new InvalidOAuthStateException();
        }

        // 2. Exchange code for user info (external HTTP — must be OUTSIDE @Transactional).
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

        // 3. Validate email.
        if (userInfo.email() == null || userInfo.email().isBlank()) {
            throw new OAuthEmailRequiredException();
        }

        // 4. Social identity lookup (read-only; runs in an auto-commit short
        //    transaction — does not pin a connection across HTTP).
        String accountId;
        boolean isNewAccount;
        Optional<com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity> existingIdentity =
                socialIdentityJpaRepository.findByProviderAndProviderUserId(
                        provider.name(), userInfo.providerUserId());

        if (existingIdentity.isPresent()) {
            accountId = existingIdentity.get().getAccountId();
            isNewAccount = false;
        } else {
            // 5. External HTTP: social signup (outside @Transactional).
            SocialSignupResult signupResult = accountServicePort.socialSignup(
                    userInfo.email(), provider.name(), userInfo.providerUserId(), userInfo.name());
            accountId = signupResult.accountId();
            isNewAccount = signupResult.newAccount();
        }

        // 6. External HTTP: account status check (outside @Transactional).
        //    Missing status is treated as account unavailable — skip the
        //    block and let the persistence step proceed (the social signup
        //    path already created the account).
        accountServicePort.getAccountStatus(accountId)
                .ifPresent(statusResult -> checkAccountStatus(statusResult.accountStatus()));

        // 7. Persistence phase: single @Transactional step covering social
        //    identity upsert, device session, refresh token, outbox.
        return oAuthLoginTransactionalStep.persist(new OAuthCallbackPersistCommand(
                userInfo, accountId, isNewAccount, ctx));
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
