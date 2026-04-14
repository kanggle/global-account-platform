package com.example.admin.infrastructure.persistence.rbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acceptance coverage for TASK-BE-028c §Caching:
 *
 * <ul>
 *   <li>2nd call hits Redis, no origin (DB) invocation</li>
 *   <li>TTL expiry → origin invoked exactly once on re-lookup</li>
 *   <li>Explicit {@code invalidate(operatorId)} → origin invoked on next lookup</li>
 *   <li>Redis outage → graceful degrade to origin</li>
 * </ul>
 *
 * <p>Boots an isolated Redis Testcontainer; does not require the full Spring
 * context. The {@link PermissionEvaluatorImpl} origin is a Mockito mock,
 * letting us count DB hits precisely.
 */
@EnabledIf("isDockerAvailable")
class PermissionEvaluatorCacheTest {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redisTemplate;

    private static final String OPERATOR = "00000000-0000-7000-8000-0000000000aa";

    private PermissionEvaluatorImpl origin;
    private CachingPermissionEvaluator caching;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        // Flush Redis between tests so TTL / key presence are deterministic.
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        origin = mock(PermissionEvaluatorImpl.class);
        when(origin.loadPermissions(OPERATOR))
                .thenReturn(Set.of("account.lock", "account.unlock", "audit.read"));

        // TTL 2s for fast-but-observable expiry assertions.
        caching = new CachingPermissionEvaluator(
                origin, redisTemplate, new ObjectMapper(), 2L, "admin:operator:perm:");
    }

    @Test
    @DisplayName("2회차 hasPermission은 Redis에서 해결되어 DB(origin) 호출이 추가되지 않는다")
    void secondCallIsCacheHitNoDbQuery() {
        assertThat(caching.hasPermission(OPERATOR, "account.lock")).isTrue();
        assertThat(caching.hasPermission(OPERATOR, "account.unlock")).isTrue();
        assertThat(caching.hasPermission(OPERATOR, "audit.read")).isTrue();

        verify(origin, times(1)).loadPermissions(OPERATOR);
    }

    @Test
    @DisplayName("10초(테스트에서는 2초) TTL 경과 후 재조회 시 origin을 1회 재호출한다")
    void reloadsAfterTtlExpiry() throws InterruptedException {
        caching.hasPermission(OPERATOR, "account.lock");
        // Sleep past TTL (2s in test config, +200ms safety margin).
        Thread.sleep(Duration.ofMillis(2_200).toMillis());
        caching.hasPermission(OPERATOR, "account.lock");

        verify(origin, times(2)).loadPermissions(OPERATOR);
    }

    @Test
    @DisplayName("invalidate(operatorId) 호출 직후 재조회 시 origin을 재호출한다")
    void invalidateForcesReload() {
        caching.hasPermission(OPERATOR, "account.lock");
        verify(origin, times(1)).loadPermissions(OPERATOR);

        caching.invalidate(OPERATOR);

        caching.hasPermission(OPERATOR, "account.lock");
        verify(origin, times(2)).loadPermissions(OPERATOR);
    }

    @Test
    @DisplayName("Redis 연결 실패 시 DB 경로로 graceful degrade")
    void redisOutageGracefullyDegrades() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        when(brokenRedis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        PermissionEvaluatorImpl localOrigin = mock(PermissionEvaluatorImpl.class);
        AtomicInteger hits = new AtomicInteger();
        doAnswer(inv -> {
            hits.incrementAndGet();
            return Set.of("audit.read");
        }).when(localOrigin).loadPermissions(anyString());

        CachingPermissionEvaluator degraded = new CachingPermissionEvaluator(
                localOrigin, brokenRedis, new ObjectMapper(), 10L, "admin:operator:perm:");

        assertThat(degraded.hasPermission(OPERATOR, "audit.read")).isTrue();
        assertThat(degraded.hasPermission(OPERATOR, "audit.read")).isTrue();

        // Each call falls through to DB when Redis is unreachable.
        assertThat(hits.get()).isEqualTo(2);
    }
}
