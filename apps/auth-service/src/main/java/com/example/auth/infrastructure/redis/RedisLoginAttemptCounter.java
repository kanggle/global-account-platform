package com.example.auth.infrastructure.redis;

import com.example.auth.domain.repository.LoginAttemptCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLoginAttemptCounter implements LoginAttemptCounter {

    private static final String KEY_PREFIX = "login:fail:";

    private final StringRedisTemplate redisTemplate;

    @Value("${auth.login.failure-window-seconds:900}")
    private long failureWindowSeconds;

    @Override
    public int getFailureCount(String emailHash) {
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + emailHash);
            return value != null ? Integer.parseInt(value) : 0;
        } catch (DataAccessException e) {
            // Redis failure: fail-open for counter reads (allow login, warn via metrics)
            log.warn("Redis unavailable for login failure counter read: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void incrementFailureCount(String emailHash) {
        try {
            String key = KEY_PREFIX + emailHash;
            redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, Duration.ofSeconds(failureWindowSeconds));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for login failure counter increment: {}", e.getMessage());
        }
    }

    @Override
    public void resetFailureCount(String emailHash) {
        try {
            redisTemplate.delete(KEY_PREFIX + emailHash);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for login failure counter reset: {}", e.getMessage());
        }
    }
}
