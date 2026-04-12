package com.example.auth.application;

import com.example.auth.application.command.LogoutCommand;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.domain.repository.TokenBlacklist;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutUseCase {

    private final TokenGeneratorPort tokenGeneratorPort;
    private final TokenBlacklist tokenBlacklist;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public void execute(LogoutCommand command) {
        String jti;
        try {
            jti = tokenGeneratorPort.extractJti(command.refreshToken());
        } catch (Exception e) {
            log.warn("Failed to parse refresh token during logout: {}", e.getMessage());
            return; // graceful - token may already be invalid
        }

        // Blacklist the refresh token in Redis
        refreshTokenRepository.findByJti(jti).ifPresent(token -> {
            long remainingTtl = token.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
            if (remainingTtl > 0) {
                tokenBlacklist.blacklist(jti, remainingTtl);
            }
            token.revoke();
            refreshTokenRepository.save(token);
        });
    }
}
