package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackCommand;
import com.example.auth.application.exception.*;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.result.AccountStatusLookupResult;
import com.example.auth.application.result.OAuthAuthorizeResult;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.SocialSignupResult;
import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.infrastructure.oauth.OAuthClient;
import com.example.auth.infrastructure.oauth.OAuthClientFactory;
import com.example.auth.infrastructure.oauth.OAuthProperties;
import com.example.auth.infrastructure.oauth.OAuthUserInfo;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TASK-BE-066: unit test for the re-scoped transactional boundary in
 * {@link OAuthLoginUseCase#callback}. Verifies that external HTTP calls
 * (OAuth provider, account-service) happen <b>before</b> the persistence
 * step is invoked, and that failures in those external calls short-circuit
 * without touching the {@link OAuthLoginTransactionalStep}.
 */
@ExtendWith(MockitoExtension.class)
class OAuthLoginUseCaseTest {

    @Mock
    private OAuthProperties oAuthProperties;
    @Mock
    private OAuthClientFactory oAuthClientFactory;
    @Mock
    private AccountServicePort accountServicePort;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SocialIdentityJpaRepository socialIdentityJpaRepository;
    @Mock
    private OAuthLoginTransactionalStep oAuthLoginTransactionalStep;
    @Mock
    private OAuthClient oAuthClient;

    @InjectMocks
    private OAuthLoginUseCase useCase;

    private static final String STATE = "state-abc";
    private static final String CODE = "auth-code-xyz";
    private static final String REDIRECT_URI = "http://localhost:3000/oauth/callback";
    private static final SessionContext CTX = new SessionContext("127.0.0.1", "Chrome/120", "fp-1");
    private static final OAuthUserInfo GOOGLE_USER = new OAuthUserInfo(
            "google-sub-1", "user@example.com", "User", OAuthProvider.GOOGLE);

    private OAuthCallbackCommand command() {
        return new OAuthCallbackCommand("google", CODE, STATE, REDIRECT_URI, CTX);
    }

    @BeforeEach
    void setUpRedisStub() {
        // StringRedisTemplate.opsForValue() is used in callback() for GETDEL;
        // only stub it in tests that actually use it to avoid unnecessary-stub errors.
    }

    @Nested
    @DisplayName("External HTTP is not wrapped in a transaction")
    class TransactionalBoundary {

        @Test
        @DisplayName("existing social identity → socialSignup is NOT called, persistence step receives existing accountId")
        void existingSocialIdentityBypassesSocialSignup() {
            stubValidState();
            stubOAuthClient(GOOGLE_USER);
            when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "google-sub-1"))
                    .thenReturn(Optional.of(socialIdentity("acc-existing-1")));
            when(accountServicePort.getAccountStatus("acc-existing-1"))
                    .thenReturn(Optional.of(new AccountStatusLookupResult("acc-existing-1", "ACTIVE")));
            OAuthLoginResult expected = new OAuthLoginResult("at", "rt", 1800, 604800, false);
            when(oAuthLoginTransactionalStep.persist(any())).thenReturn(expected);

            OAuthLoginResult actual = useCase.callback(command());

            assertThat(actual).isSameAs(expected);
            verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());

            ArgumentCaptor<OAuthCallbackPersistCommand> captor =
                    ArgumentCaptor.forClass(OAuthCallbackPersistCommand.class);
            verify(oAuthLoginTransactionalStep).persist(captor.capture());
            OAuthCallbackPersistCommand persistCommand = captor.getValue();
            assertThat(persistCommand.accountId()).isEqualTo("acc-existing-1");
            assertThat(persistCommand.newAccount()).isFalse();
            assertThat(persistCommand.userInfo()).isSameAs(GOOGLE_USER);
            assertThat(persistCommand.sessionContext()).isSameAs(CTX);
        }

        @Test
        @DisplayName("new social identity → socialSignup is called BEFORE persistence step (no txn around HTTP)")
        void newSocialIdentityCallsSocialSignupBeforePersist() {
            stubValidState();
            stubOAuthClient(GOOGLE_USER);
            when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "google-sub-1"))
                    .thenReturn(Optional.empty());
            when(accountServicePort.socialSignup("user@example.com", "GOOGLE", "google-sub-1", "User"))
                    .thenReturn(new SocialSignupResult("acc-new-2", "ACTIVE", true));
            when(accountServicePort.getAccountStatus("acc-new-2"))
                    .thenReturn(Optional.of(new AccountStatusLookupResult("acc-new-2", "ACTIVE")));
            when(oAuthLoginTransactionalStep.persist(any()))
                    .thenReturn(new OAuthLoginResult("at", "rt", 1800, 604800, true));

            useCase.callback(command());

            // Enforce ordering: external HTTP calls must happen before the
            // transactional persist step is invoked.
            InOrder order = inOrder(oAuthClient, accountServicePort, oAuthLoginTransactionalStep);
            order.verify(oAuthClient).exchangeCodeForUserInfo(eq(CODE), anyString());
            order.verify(accountServicePort).socialSignup(
                    "user@example.com", "GOOGLE", "google-sub-1", "User");
            order.verify(accountServicePort).getAccountStatus("acc-new-2");
            order.verify(oAuthLoginTransactionalStep).persist(any());

            ArgumentCaptor<OAuthCallbackPersistCommand> captor =
                    ArgumentCaptor.forClass(OAuthCallbackPersistCommand.class);
            verify(oAuthLoginTransactionalStep).persist(captor.capture());
            assertThat(captor.getValue().accountId()).isEqualTo("acc-new-2");
            assertThat(captor.getValue().newAccount()).isTrue();
        }

        @Test
        @DisplayName("OAuth provider failure → persistence step is never invoked")
        void providerFailureSkipsPersistence() {
            stubValidState();
            when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
            stubProviderPropertiesForProvider();
            when(oAuthClient.exchangeCodeForUserInfo(eq(CODE), anyString()))
                    .thenThrow(new com.example.auth.infrastructure.oauth.OAuthProviderException("boom"));

            assertThatThrownBy(() -> useCase.callback(command()))
                    .isInstanceOf(com.example.auth.infrastructure.oauth.OAuthProviderException.class);

            verifyNoInteractions(oAuthLoginTransactionalStep);
            verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());
            verify(accountServicePort, never()).getAccountStatus(anyString());
        }

        @Test
        @DisplayName("Invalid state (Redis miss) → no OAuth HTTP, no persistence")
        void invalidStateSkipsEverything() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.getAndDelete("oauth:state:" + STATE)).thenReturn(null);

            assertThatThrownBy(() -> useCase.callback(command()))
                    .isInstanceOf(InvalidOAuthStateException.class);

            verifyNoInteractions(oAuthClient, oAuthClientFactory, oAuthLoginTransactionalStep);
            verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());
            verify(accountServicePort, never()).getAccountStatus(anyString());
        }

        @Test
        @DisplayName("OAuth user info with missing email → OAuthEmailRequiredException, persistence skipped")
        void missingEmailSkipsPersistence() {
            stubValidState();
            stubOAuthClient(new OAuthUserInfo("google-sub-1", null, "User", OAuthProvider.GOOGLE));

            assertThatThrownBy(() -> useCase.callback(command()))
                    .isInstanceOf(OAuthEmailRequiredException.class);

            verifyNoInteractions(oAuthLoginTransactionalStep);
            verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());
            verify(accountServicePort, never()).getAccountStatus(anyString());
        }

        @Test
        @DisplayName("Account status LOCKED → persistence skipped")
        void lockedAccountSkipsPersistence() {
            stubValidState();
            stubOAuthClient(GOOGLE_USER);
            when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "google-sub-1"))
                    .thenReturn(Optional.of(socialIdentity("acc-locked-1")));
            when(accountServicePort.getAccountStatus("acc-locked-1"))
                    .thenReturn(Optional.of(new AccountStatusLookupResult("acc-locked-1", "LOCKED")));

            assertThatThrownBy(() -> useCase.callback(command()))
                    .isInstanceOf(AccountLockedException.class);

            verifyNoInteractions(oAuthLoginTransactionalStep);
        }

        @Test
        @DisplayName("Account status missing (empty Optional) → persistence proceeds without status check")
        void missingStatusProceeds() {
            stubValidState();
            stubOAuthClient(GOOGLE_USER);
            when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "google-sub-1"))
                    .thenReturn(Optional.of(socialIdentity("acc-x")));
            when(accountServicePort.getAccountStatus("acc-x")).thenReturn(Optional.empty());
            when(oAuthLoginTransactionalStep.persist(any()))
                    .thenReturn(new OAuthLoginResult("at", "rt", 1800, 604800, false));

            OAuthLoginResult result = useCase.callback(command());
            assertThat(result).isNotNull();
            verify(oAuthLoginTransactionalStep).persist(any());
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private void stubValidState() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth:state:" + STATE)).thenReturn("GOOGLE");
    }

    private void stubOAuthClient(OAuthUserInfo info) {
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        stubProviderPropertiesForProvider();
        when(oAuthClient.exchangeCodeForUserInfo(eq(CODE), anyString())).thenReturn(info);
    }

    private void stubProviderPropertiesForProvider() {
        // OAuthProperties.getGoogle() returns ProviderProperties — we only need
        // a non-null redirectUri when the command provides one; but callback()
        // only reads getProviderProperties(provider).getRedirectUri() when the
        // command's redirectUri is blank. Our command supplies a value, so
        // provider-property access is unused; leave this empty.
    }

    private SocialIdentityJpaEntity socialIdentity(String accountId) {
        return SocialIdentityJpaEntity.create(
                accountId, "GOOGLE", "google-sub-1", "user@example.com");
    }

    @Nested
    @DisplayName("authorize(...)")
    class Authorize {

        @Test
        @DisplayName("writes state to Redis and returns a URL containing client_id + redirect_uri + scope + state")
        void authorizeReturnsUrl() {
            OAuthProperties.ProviderProperties props = new OAuthProperties.ProviderProperties();
            props.setClientId("cid");
            props.setClientSecret("sec");
            props.setRedirectUri("http://localhost/cb");
            props.setScopes("openid,email");
            props.setAuthUri("https://auth.example/authorize");
            props.setTokenUri("https://auth.example/token");
            when(oAuthProperties.getGoogle()).thenReturn(props);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            OAuthAuthorizeResult result = useCase.authorize("google", null);

            assertThat(result.authorizationUrl()).contains("client_id=cid");
            assertThat(result.authorizationUrl()).contains("redirect_uri=http");
            assertThat(result.authorizationUrl()).contains("scope=openid+email");
            assertThat(result.authorizationUrl()).contains("state=" + result.state());
        }
    }
}
