package com.example.auth.application;

import com.example.auth.application.command.LoginCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.AccountLockedException;
import com.example.auth.application.exception.CredentialsInvalidException;
import com.example.auth.application.exception.LoginRateLimitedException;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.CredentialLookupResult;
import com.example.auth.application.result.LoginResult;
import com.example.auth.application.result.RegisterDeviceSessionResult;
import com.example.auth.domain.repository.LoginAttemptCounter;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import com.gap.security.password.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

    @Mock
    private AccountServicePort accountServicePort;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private TokenGeneratorPort tokenGeneratorPort;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private LoginAttemptCounter loginAttemptCounter;
    @Mock
    private AuthEventPublisher authEventPublisher;
    @Mock
    private RegisterOrUpdateDeviceSessionUseCase registerOrUpdateDeviceSessionUseCase;

    @InjectMocks
    private LoginUseCase loginUseCase;

    private static final String EMAIL = "test@example.com";
    private static final String PASSWORD = "password123";
    private static final String ACCOUNT_ID = "acc-123";
    private static final String HASH = "$argon2id$hash";
    private static final SessionContext CTX = new SessionContext("127.0.0.1", "Chrome/120", "fp-123");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(loginUseCase, "maxFailureCount", 5);
    }

    @Test
    @DisplayName("Login succeeds with valid credentials")
    void loginSuccess() {
        // given
        when(loginAttemptCounter.getFailureCount(anyString())).thenReturn(0);
        when(accountServicePort.lookupCredentialsByEmail(EMAIL))
                .thenReturn(Optional.of(new CredentialLookupResult(ACCOUNT_ID, HASH, "argon2id", "ACTIVE")));
        when(passwordHasher.verify(PASSWORD, HASH)).thenReturn(true);
        when(registerOrUpdateDeviceSessionUseCase.execute(eq(ACCOUNT_ID), any(SessionContext.class)))
                .thenReturn(new RegisterDeviceSessionResult("dev-1", true, java.util.List.of()));
        when(tokenGeneratorPort.generateTokenPair(eq(ACCOUNT_ID), eq("user"), eq("dev-1")))
                .thenReturn(new TokenPair("access-jwt", "refresh-jwt", 1800));
        when(tokenGeneratorPort.extractJti("refresh-jwt")).thenReturn("jti-123");
        when(tokenGeneratorPort.refreshTokenTtlSeconds()).thenReturn(604800L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        // when
        LoginResult result = loginUseCase.execute(new LoginCommand(EMAIL, PASSWORD, CTX));

        // then
        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-jwt");
        assertThat(result.expiresIn()).isEqualTo(1800);
        assertThat(result.tokenType()).isEqualTo("Bearer");

        verify(loginAttemptCounter).resetFailureCount(anyString());
        verify(authEventPublisher).publishLoginSucceeded(eq(ACCOUNT_ID), eq("jti-123"), eq(CTX));
    }

    @Test
    @DisplayName("Login fails with invalid password")
    void loginFailsInvalidPassword() {
        when(loginAttemptCounter.getFailureCount(anyString())).thenReturn(0);
        when(accountServicePort.lookupCredentialsByEmail(EMAIL))
                .thenReturn(Optional.of(new CredentialLookupResult(ACCOUNT_ID, HASH, "argon2id", "ACTIVE")));
        when(passwordHasher.verify(PASSWORD, HASH)).thenReturn(false);

        assertThatThrownBy(() -> loginUseCase.execute(new LoginCommand(EMAIL, PASSWORD, CTX)))
                .isInstanceOf(CredentialsInvalidException.class);

        verify(loginAttemptCounter).incrementFailureCount(anyString());
    }

    @Test
    @DisplayName("Login fails when account not found")
    void loginFailsAccountNotFound() {
        when(loginAttemptCounter.getFailureCount(anyString())).thenReturn(0);
        when(accountServicePort.lookupCredentialsByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginUseCase.execute(new LoginCommand(EMAIL, PASSWORD, CTX)))
                .isInstanceOf(CredentialsInvalidException.class);

        verify(loginAttemptCounter).incrementFailureCount(anyString());
    }

    @Test
    @DisplayName("Login fails when rate limited")
    void loginFailsRateLimited() {
        when(loginAttemptCounter.getFailureCount(anyString())).thenReturn(5);

        assertThatThrownBy(() -> loginUseCase.execute(new LoginCommand(EMAIL, PASSWORD, CTX)))
                .isInstanceOf(LoginRateLimitedException.class);

        verify(accountServicePort, never()).lookupCredentialsByEmail(anyString());
    }

    @Test
    @DisplayName("Login fails when account is locked")
    void loginFailsAccountLocked() {
        when(loginAttemptCounter.getFailureCount(anyString())).thenReturn(0);
        when(accountServicePort.lookupCredentialsByEmail(EMAIL))
                .thenReturn(Optional.of(new CredentialLookupResult(ACCOUNT_ID, HASH, "argon2id", "LOCKED")));

        assertThatThrownBy(() -> loginUseCase.execute(new LoginCommand(EMAIL, PASSWORD, CTX)))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    @DisplayName("Email hash is deterministic and shortened")
    void emailHashDeterministic() {
        String hash1 = LoginUseCase.hashEmail("test@example.com");
        String hash2 = LoginUseCase.hashEmail("test@example.com");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(10);
    }
}
