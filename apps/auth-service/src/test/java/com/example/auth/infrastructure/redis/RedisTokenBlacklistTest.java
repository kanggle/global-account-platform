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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisTokenBlacklist 단위 테스트")
class RedisTokenBlacklistTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private RedisTokenBlacklist blacklist;

    @BeforeEach
    void setUp() {
        blacklist = new RedisTokenBlacklist(redisTemplate);
    }

    @Test
    @DisplayName("blacklist — 정상: 키/값/TTL 로 set 호출")
    void blacklist_normal_setsKeyWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        blacklist.blacklist("jti-abc", 3600L);

        verify(valueOps).set(eq("refresh:blacklist:jti-abc"), eq("1"), eq(Duration.ofSeconds(3600L)));
    }

    @Test
    @DisplayName("blacklist — Redis 오류 → 예외 흡수 (fail-open)")
    void blacklist_redisException_swallowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new QueryTimeoutException("Redis timeout"))
                .when(valueOps).set(any(), any(), any(Duration.class));

        blacklist.blacklist("jti-err", 3600L);
    }

    @Test
    @DisplayName("isBlacklisted — 키 존재 → true")
    void isBlacklisted_keyExists_returnsTrue() {
        when(redisTemplate.hasKey("refresh:blacklist:jti-1")).thenReturn(true);

        assertThat(blacklist.isBlacklisted("jti-1")).isTrue();
    }

    @Test
    @DisplayName("isBlacklisted — 키 없음 → false")
    void isBlacklisted_keyAbsent_returnsFalse() {
        when(redisTemplate.hasKey("refresh:blacklist:jti-2")).thenReturn(false);

        assertThat(blacklist.isBlacklisted("jti-2")).isFalse();
    }

    @Test
    @DisplayName("isBlacklisted — Redis 오류 → fail-closed (true 반환)")
    void isBlacklisted_redisException_failClosed() {
        when(redisTemplate.hasKey(any())).thenThrow(new QueryTimeoutException("Redis timeout"));

        assertThat(blacklist.isBlacklisted("jti-err")).isTrue();
    }
}
