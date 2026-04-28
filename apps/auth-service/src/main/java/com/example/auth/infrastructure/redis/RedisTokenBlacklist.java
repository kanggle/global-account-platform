package com.example.auth.infrastructure.redis;

import com.example.auth.domain.repository.TokenBlacklist;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTokenBlacklist implements TokenBlacklist {

    private static final String KEY_PREFIX = "refresh:blacklist:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void blacklist(String jti, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for token blacklist write: {}", e.getMessage());
        }
    }

    @Override
    public boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (DataAccessException e) {
            // fail-closed: if Redis is unavailable, treat as blacklisted (deny refresh)
            log.warn("Redis unavailable for token blacklist check, fail-closed: {}", e.getMessage());
            return true;
        }
    }
}
