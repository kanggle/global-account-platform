package com.example.auth.infrastructure.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisLoginAttemptCounter 단위 테스트")
class RedisLoginAttemptCounterUnitTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private RedisLoginAttemptCounter counter;

    @BeforeEach
    void setUp() {
        counter = new RedisLoginAttemptCounter(redisTemplate);
        ReflectionTestUtils.setField(counter, "failureWindowSeconds", 900L);
    }

    // ── getFailureCount ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getFailureCount — 키 존재 → 파싱된 카운트 반환")
    void getFailureCount_keyExists_returnsCount() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("login:fail:hash-1")).thenReturn("5");

        assertThat(counter.getFailureCount("hash-1")).isEqualTo(5);
    }

    @Test
    @DisplayName("getFailureCount — 키 없음 → 0 반환")
    void getFailureCount_keyAbsent_returnsZero() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("login:fail:hash-2")).thenReturn(null);

        assertThat(counter.getFailureCount("hash-2")).isEqualTo(0);
    }

    @Test
    @DisplayName("getFailureCount — Redis 오류 → fail-open (0 반환)")
    void getFailureCount_redisException_failOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenThrow(new QueryTimeoutException("Redis timeout"));

        assertThat(counter.getFailureCount("hash-err")).isEqualTo(0);
    }

    // ── incrementFailureCount ──────────────────────────────────────────────────

    @Test
    @DisplayName("incrementFailureCount — 정상: increment 후 expire 호출")
    void incrementFailureCount_normal_incrementsAndSetsExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        counter.incrementFailureCount("hash-3");

        verify(valueOps).increment("login:fail:hash-3");
        verify(redisTemplate).expire(eq("login:fail:hash-3"), eq(Duration.ofSeconds(900L)));
    }

    @Test
    @DisplayName("incrementFailureCount — Redis 오류 → 예외 흡수")
    void incrementFailureCount_redisException_swallowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new QueryTimeoutException("Redis timeout")).when(valueOps).increment(any());

        counter.incrementFailureCount("hash-err");
    }

    // ── resetFailureCount ──────────────────────────────────────────────────────

    @Test
    @DisplayName("resetFailureCount — 정상: 키 삭제 호출")
    void resetFailureCount_normal_deletesKey() {
        counter.resetFailureCount("hash-4");

        verify(redisTemplate).delete("login:fail:hash-4");
    }

    @Test
    @DisplayName("resetFailureCount — Redis 오류 → 예외 흡수")
    void resetFailureCount_redisException_swallowed() {
        doThrow(new QueryTimeoutException("Redis timeout")).when(redisTemplate).delete(any(String.class));

        counter.resetFailureCount("hash-err");
    }
}
