package com.example.auth.application;

import com.example.auth.application.command.LogoutCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.repository.TokenBlacklist;
import com.example.auth.domain.token.RefreshToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogoutUseCaseTest {

    @Mock
    private TokenGeneratorPort tokenGeneratorPort;
    @Mock
    private TokenBlacklist tokenBlacklist;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private AuthEventPublisher authEventPublisher;

    @InjectMocks
    private LogoutUseCase logoutUseCase;

    private static final String ACCOUNT_ID = "acc-123";
    private static final String JTI = "jti-456";

    @Test
    @DisplayName("Logout revokes token and publishes session.revoked event")
    void logoutSuccessPublishesEvent() {
        // given
        String refreshTokenStr = "valid-refresh-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);

        RefreshToken existingToken = new RefreshToken(1L, JTI, ACCOUNT_ID,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(600000),
                null, false, "fp-123");
        when(refreshTokenRepository.findByJti(JTI)).thenReturn(Optional.of(existingToken));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        // when
        logoutUseCase.execute(new LogoutCommand(refreshTokenStr));

        // then
        verify(tokenBlacklist).blacklist(eq(JTI), anyLong());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(authEventPublisher).publishSessionRevoked(
                eq(ACCOUNT_ID),
                eq(List.of(JTI)),
                eq("USER_LOGOUT"),
                eq("user"),
                isNull(),
                any(Instant.class),
                eq(1)
        );
    }

    @Test
    @DisplayName("Logout does not publish event when token is not found")
    void logoutNoEventWhenTokenNotFound() {
        // given
        String refreshTokenStr = "unknown-refresh-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn("unknown-jti");
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(refreshTokenRepository.findByJti("unknown-jti")).thenReturn(Optional.empty());

        // when
        logoutUseCase.execute(new LogoutCommand(refreshTokenStr));

        // then
        verify(tokenBlacklist, never()).blacklist(anyString(), anyLong());
        verify(authEventPublisher, never()).publishSessionRevoked(
                anyString(), anyList(), anyString(), anyString(), any(), any(Instant.class), anyInt()
        );
    }

    @Test
    @DisplayName("Logout does not publish event when token parsing fails")
    void logoutNoEventWhenTokenParsingFails() {
        // given
        String refreshTokenStr = "malformed-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenThrow(new RuntimeException("Invalid token"));

        // when
        logoutUseCase.execute(new LogoutCommand(refreshTokenStr));

        // then
        verify(refreshTokenRepository, never()).findByJti(anyString());
        verify(authEventPublisher, never()).publishSessionRevoked(
                anyString(), anyList(), anyString(), anyString(), any(), any(Instant.class), anyInt()
        );
    }
}
