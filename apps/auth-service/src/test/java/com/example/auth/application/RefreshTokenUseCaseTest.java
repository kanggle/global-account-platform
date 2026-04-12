package com.example.auth.application;

import com.example.auth.application.command.RefreshTokenCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.SessionRevokedException;
import com.example.auth.application.exception.TokenExpiredException;
import com.example.auth.application.exception.TokenReuseDetectedException;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.RefreshTokenResult;
import com.example.auth.domain.repository.BulkInvalidationStore;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.repository.TokenBlacklist;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import com.example.auth.domain.token.TokenReuseDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

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
    private TokenReuseDetector tokenReuseDetector;
    @Mock
    private BulkInvalidationStore bulkInvalidationStore;
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
        when(bulkInvalidationStore.getInvalidatedAt(ACCOUNT_ID)).thenReturn(Optional.empty());

        RefreshToken existingToken = new RefreshToken(1L, OLD_JTI, ACCOUNT_ID,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(600000),
                null, false, "fp-123");
        when(refreshTokenRepository.findByJti(OLD_JTI)).thenReturn(Optional.of(existingToken));
        when(tokenReuseDetector.isReuse(existingToken)).thenReturn(false);
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
        verify(bulkInvalidationStore, never()).invalidateAll(anyString(), anyLong());
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
        when(bulkInvalidationStore.getInvalidatedAt(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(refreshTokenRepository.findByJti("unknown-jti")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    @DisplayName("Refresh fails when token has been revoked but no reuse chain")
    void refreshFailsRevoked() {
        String refreshTokenStr = "revoked-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(OLD_JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted(OLD_JTI)).thenReturn(false);
        when(bulkInvalidationStore.getInvalidatedAt(ACCOUNT_ID)).thenReturn(Optional.empty());

        RefreshToken revokedToken = new RefreshToken(1L, OLD_JTI, ACCOUNT_ID,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(600000),
                null, true, "fp-123");
        when(refreshTokenRepository.findByJti(OLD_JTI)).thenReturn(Optional.of(revokedToken));
        when(tokenReuseDetector.isReuse(revokedToken)).thenReturn(false);

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(SessionRevokedException.class);
    }

    @Test
    @DisplayName("Reuse detected: throws TokenReuseDetectedException, bulk revokes, emits both events, sets Redis marker")
    void refreshFailsReuseDetected() {
        String refreshTokenStr = "reused-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(OLD_JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted(OLD_JTI)).thenReturn(false);
        when(bulkInvalidationStore.getInvalidatedAt(ACCOUNT_ID)).thenReturn(Optional.empty());

        RefreshToken existingToken = new RefreshToken(1L, OLD_JTI, ACCOUNT_ID,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(600000),
                null, false, "fp-123");
        when(refreshTokenRepository.findByJti(OLD_JTI)).thenReturn(Optional.of(existingToken));
        when(tokenReuseDetector.isReuse(existingToken)).thenReturn(true);
        List<String> activeJtis = List.of(OLD_JTI, "sibling-jti-1", "sibling-jti-2");
        when(refreshTokenRepository.findActiveJtisByAccountId(ACCOUNT_ID)).thenReturn(activeJtis);
        when(refreshTokenRepository.revokeAllByAccountId(ACCOUNT_ID)).thenReturn(3);
        when(tokenGeneratorPort.refreshTokenTtlSeconds()).thenReturn(604800L);

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(TokenReuseDetectedException.class);

        verify(refreshTokenRepository).revokeAllByAccountId(ACCOUNT_ID);
        verify(bulkInvalidationStore).invalidateAll(ACCOUNT_ID, 604800L);
        verify(authEventPublisher).publishTokenReuseDetected(
                eq(ACCOUNT_ID), eq(OLD_JTI), any(), any(Instant.class),
                eq(CTX.ipMasked()), eq(CTX.deviceFingerprint()),
                eq(true), eq(3)
        );
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> jtisCaptor = ArgumentCaptor.forClass(List.class);
        verify(authEventPublisher).publishSessionRevoked(
                eq(ACCOUNT_ID), jtisCaptor.capture(), eq("TOKEN_REUSE_DETECTED"),
                eq("system"), isNull(), any(Instant.class), eq(3)
        );
        assertThat(jtisCaptor.getValue())
                .containsExactlyInAnyOrderElementsOf(activeJtis);
    }

    @Test
    @DisplayName("Refresh fails with SESSION_REVOKED when invalidate-all marker exists and token iat precedes it")
    void refreshFailsWhenInvalidateAllMarkerPredatesToken() {
        String refreshTokenStr = "stale-token";
        Instant markerAt = Instant.now().minusSeconds(60);
        Instant tokenIat = markerAt.minusSeconds(60); // issued before the marker
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(OLD_JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted(OLD_JTI)).thenReturn(false);
        when(bulkInvalidationStore.getInvalidatedAt(ACCOUNT_ID)).thenReturn(Optional.of(markerAt));
        when(tokenGeneratorPort.extractIssuedAt(refreshTokenStr)).thenReturn(tokenIat);

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(SessionRevokedException.class);

        verify(refreshTokenRepository, never()).findByJti(anyString());
    }

    @Test
    @DisplayName("Reuse on already-revoked chain is idempotent: throws 401 but does not re-emit events")
    void refreshReuseIdempotentOnAlreadyRevoked() {
        String refreshTokenStr = "reused-revoked-token";
        when(tokenGeneratorPort.extractJti(refreshTokenStr)).thenReturn(OLD_JTI);
        when(tokenGeneratorPort.extractAccountId(refreshTokenStr)).thenReturn(ACCOUNT_ID);
        when(tokenBlacklist.isBlacklisted(OLD_JTI)).thenReturn(false);
        when(bulkInvalidationStore.getInvalidatedAt(ACCOUNT_ID)).thenReturn(Optional.empty());

        RefreshToken existingToken = new RefreshToken(1L, OLD_JTI, ACCOUNT_ID,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(600000),
                null, true, "fp-123"); // already revoked
        when(refreshTokenRepository.findByJti(OLD_JTI)).thenReturn(Optional.of(existingToken));
        when(tokenReuseDetector.isReuse(existingToken)).thenReturn(true);
        when(refreshTokenRepository.findActiveJtisByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        when(refreshTokenRepository.revokeAllByAccountId(ACCOUNT_ID)).thenReturn(0);
        when(tokenGeneratorPort.refreshTokenTtlSeconds()).thenReturn(604800L);

        assertThatThrownBy(() -> refreshTokenUseCase.execute(
                new RefreshTokenCommand(refreshTokenStr, CTX)))
                .isInstanceOf(TokenReuseDetectedException.class);

        verify(bulkInvalidationStore).invalidateAll(ACCOUNT_ID, 604800L);
        verify(authEventPublisher, never()).publishTokenReuseDetected(
                anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), anyInt());
        verify(authEventPublisher, never()).publishSessionRevoked(
                anyString(), anyList(), anyString(), anyString(), any(), any(Instant.class), anyInt());
    }
}
