package com.example.membership.infrastructure.redis;

import com.example.membership.application.idempotency.IdempotencyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "membership:idem:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisIdempotencyStore(StringRedisTemplate redis,
                                 @Value("${membership.idempotency.ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public boolean putIfAbsent(String key, String subscriptionId) {
        Boolean ok = redis.opsForValue().setIfAbsent(KEY_PREFIX + key, subscriptionId, ttl);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(KEY_PREFIX + key));
    }
}
