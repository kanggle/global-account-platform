package com.example.auth.application;

import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.domain.repository.BulkInvalidationStore;
import com.example.auth.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForceLogoutUseCase {

    static final String ACCESS_INVALIDATE_KEY_PREFIX = "access:invalidate-before:";

    private final RefreshTokenRepository refreshTokenRepository;
    private final BulkInvalidationStore bulkInvalidationStore;
    private final TokenGeneratorPort tokenGeneratorPort;
    private final StringRedisTemplate stringRedisTemplate;

    @Transactional
    public Result execute(String accountId) {
        int revokedCount = refreshTokenRepository.revokeAllByAccountId(accountId);
        Instant revokedAt = Instant.now();
        bulkInvalidationStore.invalidateAll(accountId, tokenGeneratorPort.refreshTokenTtlSeconds());
        setAccessInvalidation(accountId, revokedAt);
        return new Result(accountId, revokedCount, revokedAt);
    }

    private void setAccessInvalidation(String accountId, Instant at) {
        try {
            stringRedisTemplate.opsForValue().set(
                    ACCESS_INVALIDATE_KEY_PREFIX + accountId,
                    String.valueOf(at.toEpochMilli()),
                    Duration.ofSeconds(tokenGeneratorPort.accessTokenTtlSeconds()));
        } catch (Exception e) {
            log.warn("Redis unavailable for access invalidation write: {}", e.getMessage());
        }
    }

    public record Result(String accountId, int revokedTokenCount, Instant revokedAt) {}
}
