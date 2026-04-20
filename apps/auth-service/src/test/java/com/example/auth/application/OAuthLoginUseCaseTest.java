package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackCommand;
import com.example.auth.application.command.OAuthCallbackTxnCommand;
import com.example.auth.application.exception.InvalidOAuthStateException;
import com.example.auth.application.exception.OAuthEmailRequiredException;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.result.AccountStatusLookupResult;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.SocialSignupResult;
import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.infrastructure.oauth.OAuthClient;
import com.example.auth.infrastructure.oauth.OAuthClientFactory;
import com.example.auth.infrastructure.oauth.OAuthProperties;
import com.example.auth.infrastructure.oauth.OAuthProviderException;
import com.example.auth.infrastructure.oauth.OAuthUserInfo;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OAuthLoginUseCase} — orchestration layer.
 *
 * <p>TASK-BE-069 + TASK-BE-072 guarantees:
 * <ul>
 *   <li>External provider HTTP (token+userinfo) is invoked BEFORE the
 *       {@link OAuthLoginTransactionalStep} (which owns the DB transaction).</li>
 *   <li>Internal account-service HTTP ({@code socialSignup} on new-identity path,
 *       {@code getAccountStatus} always) is invoked BEFORE the transactional step.</li>
 *   <li>When any of those HTTP calls fails, the transactional step is not invoked —
 *       no DB writes happen.</li>
 *   <li>The txn step receives the already-fetched provider data AND the resolved
 *       {@code accountId} / {@code isNewAccount} / {@code accountStatus} via
 *       {@link OAuthCallbackTxnCommand}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OAuthLoginUseCaseTest {

    @Mock private OAuthProperties oAuthProperties;
    @Mock private OAuthClientFactory oAuthClientFactory;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private OAuthLoginTransactionalStep oAuthLoginTransactionalStep;
    @Mock private AccountServicePort accountServicePort;
    @Mock private SocialIdentityJpaRepository socialIdentityJpaRepository;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private OAuthClient oAuthClient;

    @InjectMocks
    private OAuthLoginUseCase oAuthLoginUseCase;

    private static final String STATE = "state-abc";
    private static final String STATE_KEY = "oauth:state:" + STATE;
    private static final String CODE = "auth-code-123";
    private static final String REDIRECT_URI = "http://localhost:3000/oauth/callback";
    private static final SessionContext CTX = new SessionContext("127.0.0.1", "Chrome/120", "fp-1");
    private static final OAuthUserInfo USER_INFO = new OAuthUserInfo(
            "provider-user-1", "user@example.com", "User", OAuthProvider.GOOGLE);

    private OAuthCallbackCommand command;

    @BeforeEach
    void setUp() {
        command = new OAuthCallbackCommand("GOOGLE", CODE, STATE, REDIRECT_URI, CTX);
    }

    @Test
    @DisplayName("callback (new identity): provider HTTP → socialSignup HTTP → getAccountStatus HTTP "
            + "→ persistLogin, txn command carries resolved accountId/isNewAccount/accountStatus")
    void callback_newIdentity_allHttpBeforeTransactionalStep() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenReturn(USER_INFO);
        when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "provider-user-1"))
                .thenReturn(Optional.empty());
        when(accountServicePort.socialSignup(
                "user@example.com", "GOOGLE", "provider-user-1", "User"))
                .thenReturn(new SocialSignupResult("acc-123", "ACTIVE", true));
        when(accountServicePort.getAccountStatus("acc-123"))
                .thenReturn(Optional.of(new AccountStatusLookupResult("acc-123", "ACTIVE")));
        OAuthLoginResult expected = new OAuthLoginResult(
                "access-jwt", "refresh-jwt", 1800, 604800L, true);
        when(oAuthLoginTransactionalStep.persistLogin(any(OAuthCallbackTxnCommand.class)))
                .thenReturn(expected);

        // when
        OAuthLoginResult result = oAuthLoginUseCase.callback(command);

        // then — all HTTP (provider + account-service) must happen BEFORE the transactional step
        InOrder order = inOrder(oAuthClient, accountServicePort, oAuthLoginTransactionalStep);
        order.verify(oAuthClient).exchangeCodeForUserInfo(CODE, REDIRECT_URI);
        order.verify(accountServicePort).socialSignup(
                "user@example.com", "GOOGLE", "provider-user-1", "User");
        order.verify(accountServicePort).getAccountStatus("acc-123");
        order.verify(oAuthLoginTransactionalStep).persistLogin(any(OAuthCallbackTxnCommand.class));

        // and the txn command carries all pre-resolved data
        ArgumentCaptor<OAuthCallbackTxnCommand> captor =
                ArgumentCaptor.forClass(OAuthCallbackTxnCommand.class);
        verify(oAuthLoginTransactionalStep).persistLogin(captor.capture());
        OAuthCallbackTxnCommand txnCommand = captor.getValue();
        assertThat(txnCommand.provider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(txnCommand.userInfo()).isEqualTo(USER_INFO);
        assertThat(txnCommand.sessionContext()).isEqualTo(CTX);
        assertThat(txnCommand.accountId()).isEqualTo("acc-123");
        assertThat(txnCommand.isNewAccount()).isTrue();
        assertThat(txnCommand.accountStatus()).contains("ACTIVE");

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("callback (existing identity): socialSignup NOT called, accountId taken from "
            + "pre-existing SocialIdentityJpaEntity, getAccountStatus still runs before txn")
    void callback_existingIdentity_skipsSocialSignup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenReturn(USER_INFO);
        var existing = SocialIdentityJpaEntity.create(
                "acc-existing", "GOOGLE", "provider-user-1", "user@example.com");
        when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "provider-user-1"))
                .thenReturn(Optional.of(existing));
        when(accountServicePort.getAccountStatus("acc-existing"))
                .thenReturn(Optional.of(new AccountStatusLookupResult("acc-existing", "ACTIVE")));
        when(oAuthLoginTransactionalStep.persistLogin(any(OAuthCallbackTxnCommand.class)))
                .thenReturn(new OAuthLoginResult("a", "r", 1, 1L, false));

        oAuthLoginUseCase.callback(command);

        // socialSignup must NOT be called on the existing-identity path
        verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());

        // Ordering: provider HTTP → getAccountStatus → persistLogin
        InOrder order = inOrder(oAuthClient, accountServicePort, oAuthLoginTransactionalStep);
        order.verify(oAuthClient).exchangeCodeForUserInfo(CODE, REDIRECT_URI);
        order.verify(accountServicePort).getAccountStatus("acc-existing");
        order.verify(oAuthLoginTransactionalStep).persistLogin(any(OAuthCallbackTxnCommand.class));

        ArgumentCaptor<OAuthCallbackTxnCommand> captor =
                ArgumentCaptor.forClass(OAuthCallbackTxnCommand.class);
        verify(oAuthLoginTransactionalStep).persistLogin(captor.capture());
        assertThat(captor.getValue().accountId()).isEqualTo("acc-existing");
        assertThat(captor.getValue().isNewAccount()).isFalse();
        assertThat(captor.getValue().accountStatus()).contains("ACTIVE");
    }

    @Test
    @DisplayName("callback: getAccountStatus returns empty → txn command carries empty status; "
            + "txn step decides what to do with it")
    void callback_accountStatusEmpty_propagatesEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenReturn(USER_INFO);
        when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "provider-user-1"))
                .thenReturn(Optional.empty());
        when(accountServicePort.socialSignup(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SocialSignupResult("acc-new", "ACTIVE", true));
        when(accountServicePort.getAccountStatus("acc-new")).thenReturn(Optional.empty());
        when(oAuthLoginTransactionalStep.persistLogin(any(OAuthCallbackTxnCommand.class)))
                .thenReturn(new OAuthLoginResult("a", "r", 1, 1L, true));

        oAuthLoginUseCase.callback(command);

        ArgumentCaptor<OAuthCallbackTxnCommand> captor =
                ArgumentCaptor.forClass(OAuthCallbackTxnCommand.class);
        verify(oAuthLoginTransactionalStep).persistLogin(captor.capture());
        assertThat(captor.getValue().accountStatus()).isEmpty();
    }

    @Test
    @DisplayName("callback: invalid state short-circuits — no HTTP call, no txn step")
    void callback_invalidStateSkipsHttpAndTxn() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn(null);

        assertThatThrownBy(() -> oAuthLoginUseCase.callback(command))
                .isInstanceOf(InvalidOAuthStateException.class);

        verify(oAuthClientFactory, never()).getClient(any());
        verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());
        verify(accountServicePort, never()).getAccountStatus(anyString());
        verify(oAuthLoginTransactionalStep, never()).persistLogin(any());
    }

    @Test
    @DisplayName("callback: provider HTTP failure propagates; account-service HTTP and txn step NOT called")
    void callback_providerHttpFailure_skipsAccountServiceAndTxn() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        OAuthProviderException providerFailure =
                new OAuthProviderException("google token exchange failed");
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenThrow(providerFailure);

        assertThatThrownBy(() -> oAuthLoginUseCase.callback(command))
                .isSameAs(providerFailure);

        verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());
        verify(accountServicePort, never()).getAccountStatus(anyString());
        verify(oAuthLoginTransactionalStep, never()).persistLogin(any());
    }

    @Test
    @DisplayName("callback: empty email from provider → reject BEFORE account-service HTTP and txn step")
    void callback_emptyEmail_skipsAccountServiceAndTxn() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        OAuthUserInfo noEmail = new OAuthUserInfo(
                "provider-user-1", "", "User", OAuthProvider.GOOGLE);
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenReturn(noEmail);

        assertThatThrownBy(() -> oAuthLoginUseCase.callback(command))
                .isInstanceOf(OAuthEmailRequiredException.class);

        verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());
        verify(accountServicePort, never()).getAccountStatus(anyString());
        verify(oAuthLoginTransactionalStep, never()).persistLogin(any());
    }

    @Test
    @DisplayName("callback: when the transactional step fails, HTTP calls are NOT retried "
            + "(already performed — single-shot semantics preserved)")
    void callback_txnFailure_httpNotRetried() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenReturn(USER_INFO);
        when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "provider-user-1"))
                .thenReturn(Optional.empty());
        when(accountServicePort.socialSignup(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SocialSignupResult("acc-1", "ACTIVE", true));
        when(accountServicePort.getAccountStatus("acc-1"))
                .thenReturn(Optional.of(new AccountStatusLookupResult("acc-1", "ACTIVE")));
        RuntimeException dbFailure = new RuntimeException("db down");
        when(oAuthLoginTransactionalStep.persistLogin(any(OAuthCallbackTxnCommand.class)))
                .thenThrow(dbFailure);

        assertThatThrownBy(() -> oAuthLoginUseCase.callback(command))
                .isSameAs(dbFailure);

        // HTTP fetches happened exactly once; no retry after txn failure
        verify(oAuthClient).exchangeCodeForUserInfo(CODE, REDIRECT_URI);
        verify(accountServicePort).socialSignup("user@example.com", "GOOGLE", "provider-user-1", "User");
        verify(accountServicePort).getAccountStatus("acc-1");
    }

    @Test
    @DisplayName("callback: redirectUri blank → falls back to provider properties default "
            + "and still calls HTTP before txn")
    void callback_redirectUriFallback() {
        OAuthProperties.ProviderProperties google = new OAuthProperties.ProviderProperties();
        google.setRedirectUri("http://default/callback");
        when(oAuthProperties.getGoogle()).thenReturn(google);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        when(oAuthClient.exchangeCodeForUserInfo(anyString(), anyString())).thenReturn(USER_INFO);
        when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "provider-user-1"))
                .thenReturn(Optional.empty());
        when(accountServicePort.socialSignup(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SocialSignupResult("acc-1", "ACTIVE", true));
        when(accountServicePort.getAccountStatus("acc-1"))
                .thenReturn(Optional.of(new AccountStatusLookupResult("acc-1", "ACTIVE")));
        when(oAuthLoginTransactionalStep.persistLogin(any()))
                .thenReturn(new OAuthLoginResult("a", "r", 1, 1L, false));

        OAuthCallbackCommand blankRedirect = new OAuthCallbackCommand(
                "GOOGLE", CODE, STATE, "", CTX);
        oAuthLoginUseCase.callback(blankRedirect);

        verify(oAuthClient).exchangeCodeForUserInfo(CODE, "http://default/callback");
    }
}
