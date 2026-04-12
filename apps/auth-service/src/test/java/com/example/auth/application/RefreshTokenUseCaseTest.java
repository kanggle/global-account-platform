package com.example.auth.application;

import com.example.auth.application.command.RefreshTokenCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.SessionRevokedException;
import com.example.auth.application.exception.TokenExpiredException;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.RefreshTokenResult;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.repository.TokenBlacklist;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenUseCaseTest {

    @Mock
    private TokenGeneratorPort tokenGeneratorPort;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private TokenBlacklist tokenBlacklist;
    @Mock
    private AuthEventPublisher authEventPublisher;

    @InjectMocks
    private RefreshTokenUseCase refreshTokenUseCase;

    private static final String ACCOUNT_ID = "acc-123";
    private static final String OLD_JTI = "old-jti";
    private static final String NEW_JTI = "new-jti";
    private static final SessionContext CTX = new SessionContext("127.0.0.1", "Chrome/120", "fp-123");

    @Test
    @DisplayName("Refresh token rotation succeeds")
    void refreshSuccess() {
        // given
        String refreshTokenStr = "old-refresh-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(OLD_JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted(OLD_JTI)).thenReturn(false);

        RefreshToken existingToken = new RefreshToken(1L, OLD_JTI, ACCOUNT_ID,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(600000),
                null, false, "fp-123");
        when(refreshTokenRepository.findByJti(OLD_JTI)).thenReturn(Optional.of(existingToken));
        when(refreshTokenRepository.existsByRotatedFrom(OLD_JTI)).thenReturn(false);
        when(tokenGeneratorPort.generateTokenPair(ACCOUNT_ID, "user"))
                .thenReturn(new TokenPair("new-access", "new-refresh", 1800));
        when(tokenGeneratorPort.extractJti("new-refresh")).thenReturn(NEW_JTI);
        when(tokenGeneratorPort.refreshTokenTtlSeconds()).thenReturn(604800L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        // when
        RefreshTokenResult result = refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX));

        // then
        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
        verify(tokenBlacklist).blacklist(eq(OLD_JTI), anyLong());
        verify(authEventPublisher).publishTokenRefreshed(ACCOUNT_ID, OLD_JTI, NEW_JTI, CTX);
    }

    @Test
    @DisplayName("Refresh fails when token is blacklisted")
    void refreshFailsBlacklisted() {
        String refreshTokenStr = "blacklisted-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(OLD_JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted(OLD_JTI)).thenReturn(true);

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(SessionRevokedException.class);
    }

    @Test
    @DisplayName("Refresh fails when token not found in DB")
    void refreshFailsNotFound() {
        String refreshTokenStr = "unknown-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn("unknown-jti");
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted("unknown-jti")).thenReturn(false);
        when(refreshTokenRepository.findByJti("unknown-jti")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    @DisplayName("Refresh fails when token has been revoked")
    void refreshFailsRevoked() {
        String refreshTokenStr = "revoked-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(OLD_JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted(OLD_JTI)).thenReturn(false);

        RefreshToken revokedToken = new RefreshToken(1L, OLD_JTI, ACCOUNT_ID,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(600000),
                null, true, "fp-123");
        when(refreshTokenRepository.findByJti(OLD_JTI)).thenReturn(Optional.of(revokedToken));

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(SessionRevokedException.class);
    }

    @Test
    @DisplayName("Refresh fails on token reuse detection")
    void refreshFailsReuseDetected() {
        String refreshTokenStr = "reused-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(OLD_JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted(OLD_JTI)).thenReturn(false);

        RefreshToken existingToken = new RefreshToken(1L, OLD_JTI, ACCOUNT_ID,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(600000),
                null, false, "fp-123");
        when(refreshTokenRepository.findByJti(OLD_JTI)).thenReturn(Optional.of(existingToken));
        when(refreshTokenRepository.existsByRotatedFrom(OLD_JTI)).thenReturn(true); // already rotated!

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(SessionRevokedException.class);

        verify(refreshTokenRepository).revokeAllByAccountId(ACCOUNT_ID);
        verify(authEventPublisher).publishTokenReuseDetected(
                eq(ACCOUNT_ID), eq(OLD_JTI), any(), any(Instant.class),
                eq(CTX.ipMasked()), eq(CTX.deviceFingerprint()),
                eq(true), anyInt()
        );
    }
}
