package com.example.auth.application;

import com.example.auth.application.command.OAuthCallbackTxnCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.AccountLockedException;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.AccountStatusLookupResult;
import com.example.auth.application.result.OAuthLoginResult;
import com.example.auth.application.result.RegisterDeviceSessionResult;
import com.example.auth.application.result.SocialSignupResult;
import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import com.example.auth.infrastructure.oauth.OAuthUserInfo;
import com.example.auth.infrastructure.persistence.SocialIdentityJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link OAuthLoginTransactionalStep}.
 *
 * <p>Confirms the DB-side steps (social identity upsert, account status check,
 * device session registration, refresh token persist, event publish) execute
 * against the input command and do NOT touch any external HTTP client.
 */
@ExtendWith(MockitoExtension.class)
class OAuthLoginTransactionalStepTest {

    @Mock private AccountServicePort accountServicePort;
    @Mock private TokenGeneratorPort tokenGeneratorPort;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private AuthEventPublisher authEventPublisher;
    @Mock private RegisterOrUpdateDeviceSessionUseCase registerOrUpdateDeviceSessionUseCase;
    @Mock private SocialIdentityJpaRepository socialIdentityJpaRepository;

    @InjectMocks
    private OAuthLoginTransactionalStep step;

    private static final SessionContext CTX =
            new SessionContext("127.0.0.1", "Chrome/120", "fp-1");
    private static final OAuthUserInfo USER_INFO = new OAuthUserInfo(
            "provider-user-1", "user@example.com", "User", OAuthProvider.GOOGLE);

    @Test
    @DisplayName("persistLogin: new social identity → calls socialSignup, creates identity, "
            + "persists refresh token, publishes events")
    void persistLogin_newSocialIdentity() {
        OAuthCallbackTxnCommand command =
                new OAuthCallbackTxnCommand(OAuthProvider.GOOGLE, USER_INFO, CTX);
        when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "provider-user-1"))
                .thenReturn(Optional.empty());
        when(accountServicePort.socialSignup(
                "user@example.com", "GOOGLE", "provider-user-1", "User"))
                .thenReturn(new SocialSignupResult("acc-123", "ACTIVE", true));
        when(accountServicePort.getAccountStatus("acc-123"))
                .thenReturn(Optional.of(new AccountStatusLookupResult("acc-123", "ACTIVE")));
        when(registerOrUpdateDeviceSessionUseCase.execute(eq("acc-123"), any(SessionContext.class)))
                .thenReturn(new RegisterDeviceSessionResult("dev-1", true, List.of()));
        when(tokenGeneratorPort.generateTokenPair("acc-123", "user", "dev-1"))
                .thenReturn(new TokenPair("access-jwt", "refresh-jwt", 1800));
        when(tokenGeneratorPort.extractJti("refresh-jwt")).thenReturn("jti-1");
        when(tokenGeneratorPort.refreshTokenTtlSeconds()).thenReturn(604800L);

        OAuthLoginResult result = step.persistLogin(command);

        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-jwt");
        assertThat(result.isNewAccount()).isTrue();

        verify(socialIdentityJpaRepository).save(any());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(authEventPublisher).publishLoginSucceeded(
                eq("acc-123"), eq("jti-1"), any(SessionContext.class),
                eq("dev-1"), eq(true), eq("OAUTH_GOOGLE"));
        verify(authEventPublisher).publishAuthSessionCreated(
                eq("acc-123"), eq("dev-1"), eq("jti-1"),
                anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("persistLogin: existing social identity → no socialSignup call, "
            + "returns newAccount=false")
    void persistLogin_existingSocialIdentity() {
        OAuthCallbackTxnCommand command =
                new OAuthCallbackTxnCommand(OAuthProvider.GOOGLE, USER_INFO, CTX);
        var existing = com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity.create(
                "acc-existing", "GOOGLE", "provider-user-1", "user@example.com");
        when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "provider-user-1"))
                .thenReturn(Optional.of(existing));
        when(accountServicePort.getAccountStatus("acc-existing"))
                .thenReturn(Optional.of(new AccountStatusLookupResult("acc-existing", "ACTIVE")));
        when(registerOrUpdateDeviceSessionUseCase.execute(eq("acc-existing"), any(SessionContext.class)))
                .thenReturn(new RegisterDeviceSessionResult("dev-2", false, List.of()));
        when(tokenGeneratorPort.generateTokenPair("acc-existing", "user", "dev-2"))
                .thenReturn(new TokenPair("a", "r", 1));
        when(tokenGeneratorPort.extractJti("r")).thenReturn("jti-x");
        when(tokenGeneratorPort.refreshTokenTtlSeconds()).thenReturn(1L);

        OAuthLoginResult result = step.persistLogin(command);

        assertThat(result.isNewAccount()).isFalse();
        verify(accountServicePort, never()).socialSignup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("persistLogin: account LOCKED → AccountLockedException BEFORE token issuance")
    void persistLogin_accountLocked() {
        OAuthCallbackTxnCommand command =
                new OAuthCallbackTxnCommand(OAuthProvider.GOOGLE, USER_INFO, CTX);
        var existing = com.example.auth.infrastructure.persistence.SocialIdentityJpaEntity.create(
                "acc-locked", "GOOGLE", "provider-user-1", "user@example.com");
        when(socialIdentityJpaRepository.findByProviderAndProviderUserId("GOOGLE", "provider-user-1"))
                .thenReturn(Optional.of(existing));
        when(accountServicePort.getAccountStatus("acc-locked"))
                .thenReturn(Optional.of(new AccountStatusLookupResult("acc-locked", "LOCKED")));

        assertThatThrownBy(() -> step.persistLogin(command))
                .isInstanceOf(AccountLockedException.class);

        verify(registerOrUpdateDeviceSessionUseCase, never()).execute(anyString(), any());
        verify(refreshTokenRepository, never()).save(any());
        verify(tokenGeneratorPort, never()).generateTokenPair(anyString(), anyString(), anyString());
    }
}
