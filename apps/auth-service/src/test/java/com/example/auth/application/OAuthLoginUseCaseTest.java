package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackCommand;
import com.example.auth.application.command.OAuthCallbackTxnCommand;
import com.example.auth.application.exception.InvalidOAuthStateException;
import com.example.auth.application.exception.OAuthEmailRequiredException;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.infrastructure.oauth.OAuthClient;
import com.example.auth.infrastructure.oauth.OAuthClientFactory;
import com.example.auth.infrastructure.oauth.OAuthProperties;
import com.example.auth.infrastructure.oauth.OAuthProviderException;
import com.example.auth.infrastructure.oauth.OAuthUserInfo;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OAuthLoginUseCase} — orchestration layer.
 *
 * <p>TASK-BE-069 guarantees:
 * <ul>
 *   <li>External provider HTTP (token+userinfo) is invoked BEFORE the
 *       {@link OAuthLoginTransactionalStep} (which owns the DB transaction).</li>
 *   <li>When the HTTP call fails, the transactional step is not invoked —
 *       no DB writes happen.</li>
 *   <li>The txn step receives the already-fetched provider data via
 *       {@link OAuthCallbackTxnCommand}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OAuthLoginUseCaseTest {

    @Mock private OAuthProperties oAuthProperties;
    @Mock private OAuthClientFactory oAuthClientFactory;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private OAuthLoginTransactionalStep oAuthLoginTransactionalStep;
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
    @DisplayName("callback: external HTTP runs BEFORE the transactional step, and txn step "
            + "receives provider data via OAuthCallbackTxnCommand")
    void callback_httpCallHappensBeforeTransactionalStep() {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenReturn(USER_INFO);
        OAuthLoginResult expected = new OAuthLoginResult(
                "access-jwt", "refresh-jwt", 1800, 604800L, false);
        when(oAuthLoginTransactionalStep.persistLogin(any(OAuthCallbackTxnCommand.class)))
                .thenReturn(expected);

        // when
        OAuthLoginResult result = oAuthLoginUseCase.callback(command);

        // then — external HTTP must happen BEFORE the transactional step
        InOrder order = inOrder(oAuthClient, oAuthLoginTransactionalStep);
        order.verify(oAuthClient).exchangeCodeForUserInfo(CODE, REDIRECT_URI);
        order.verify(oAuthLoginTransactionalStep).persistLogin(any(OAuthCallbackTxnCommand.class));

        // and the txn step receives the fetched provider data verbatim
        ArgumentCaptor<OAuthCallbackTxnCommand> captor =
                ArgumentCaptor.forClass(OAuthCallbackTxnCommand.class);
        verify(oAuthLoginTransactionalStep).persistLogin(captor.capture());
        OAuthCallbackTxnCommand txnCommand = captor.getValue();
        assertThat(txnCommand.provider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(txnCommand.userInfo()).isEqualTo(USER_INFO);
        assertThat(txnCommand.sessionContext()).isEqualTo(CTX);

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("callback: invalid state short-circuits — no HTTP call, no txn step")
    void callback_invalidStateSkipsHttpAndTxn() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn(null);

        assertThatThrownBy(() -> oAuthLoginUseCase.callback(command))
                .isInstanceOf(InvalidOAuthStateException.class);

        verify(oAuthClientFactory, never()).getClient(any());
        verify(oAuthLoginTransactionalStep, never()).persistLogin(any());
    }

    @Test
    @DisplayName("callback: HTTP failure propagates and the txn step is NOT called "
            + "(no DB writes attempted)")
    void callback_httpFailure_skipsTransactionalStep() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        OAuthProviderException providerFailure =
                new OAuthProviderException("google token exchange failed");
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenThrow(providerFailure);

        assertThatThrownBy(() -> oAuthLoginUseCase.callback(command))
                .isSameAs(providerFailure);

        verify(oAuthLoginTransactionalStep, never()).persistLogin(any());
    }

    @Test
    @DisplayName("callback: empty email from provider → reject BEFORE txn step")
    void callback_emptyEmail_skipsTransactionalStep() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        OAuthUserInfo noEmail = new OAuthUserInfo(
                "provider-user-1", "", "User", OAuthProvider.GOOGLE);
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenReturn(noEmail);

        assertThatThrownBy(() -> oAuthLoginUseCase.callback(command))
                .isInstanceOf(OAuthEmailRequiredException.class);

        verify(oAuthLoginTransactionalStep, never()).persistLogin(any());
    }

    @Test
    @DisplayName("callback: when the transactional step fails, the HTTP call is NOT retried "
            + "(already performed — single-shot semantics preserved)")
    void callback_txnFailure_httpNotRetried() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(STATE_KEY)).thenReturn("GOOGLE");
        when(oAuthClientFactory.getClient(OAuthProvider.GOOGLE)).thenReturn(oAuthClient);
        when(oAuthClient.exchangeCodeForUserInfo(CODE, REDIRECT_URI)).thenReturn(USER_INFO);
        RuntimeException dbFailure = new RuntimeException("db down");
        when(oAuthLoginTransactionalStep.persistLogin(any(OAuthCallbackTxnCommand.class)))
                .thenThrow(dbFailure);

        assertThatThrownBy(() -> oAuthLoginUseCase.callback(command))
                .isSameAs(dbFailure);

        // HTTP fetch happened exactly once; no retry after txn failure
        verify(oAuthClient).exchangeCodeForUserInfo(CODE, REDIRECT_URI);
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
        when(oAuthLoginTransactionalStep.persistLogin(any()))
                .thenReturn(new OAuthLoginResult("a", "r", 1, 1L, false));

        OAuthCallbackCommand blankRedirect = new OAuthCallbackCommand(
                "GOOGLE", CODE, STATE, "", CTX);
        oAuthLoginUseCase.callback(blankRedirect);

        verify(oAuthClient).exchangeCodeForUserInfo(CODE, "http://default/callback");
    }
}
