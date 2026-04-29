package com.example.gateway.filter;

import com.example.gateway.ratelimit.TokenBucketRateLimiter;
import com.example.gateway.ratelimit.TokenBucketRateLimiter.RateLimitResult;
import com.example.gateway.route.RouteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter 단위 테스트")
class RateLimitFilterUnitTest {

    @Mock
    private TokenBucketRateLimiter rateLimiter;

    @Mock
    private RouteConfig routeConfig;

    @Mock
    private GatewayFilterChain chain;

    private RateLimitFilter filter;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        filter = new RateLimitFilter(rateLimiter, routeConfig, objectMapper, meterRegistry);
    }

    // -----------------------------------------------------------------------
    // Existing behaviour tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("rate limit 이내 요청은 정상 통과")
    void filter_underLimit_passesThrough() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.100", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/auth/login")).willReturn("login");
        given(rateLimiter.isAllowed(eq("login"), anyString()))
                .willReturn(Mono.just(RateLimitResult.allowed()));
        given(rateLimiter.isAllowed(eq("global"), anyString()))
                .willReturn(Mono.just(RateLimitResult.allowed()));
        given(chain.filter(any())).willReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("scope rate limit 초과 시 429 + Retry-After 반환")
    void filter_scopeOverLimit_returns429WithRetryAfter() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.100", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/auth/login")).willReturn("login");
        given(rateLimiter.isAllowed(eq("login"), anyString()))
                .willReturn(Mono.just(RateLimitResult.rejected(60)));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("global rate limit 초과 시 429 반환")
    void filter_globalOverLimit_returns429() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/accounts/me")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.100", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/accounts/me")).willReturn(null);
        given(rateLimiter.isAllowed(eq("global"), anyString()))
                .willReturn(Mono.just(RateLimitResult.rejected(1)));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("refresh scope는 JWT sub 클레임의 account_id를 rate-limit 식별자로 사용")
    void filter_refreshScope_extractsAccountIdFromJwtSub() {
        // Build a minimal JWT with sub = "account-42" (header.payload.signature)
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"account-42\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
        String fakeJwt = header + "." + payload + ".fake-signature";

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/refresh")
                .header("Authorization", "Bearer " + fakeJwt)
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/auth/refresh")).willReturn("refresh");
        given(rateLimiter.isAllowed(eq("refresh"), eq("account-42")))
                .willReturn(Mono.just(RateLimitResult.allowed()));
        given(rateLimiter.isAllowed(eq("global"), anyString()))
                .willReturn(Mono.just(RateLimitResult.allowed()));
        given(chain.filter(any())).willReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Verify the rate limiter was called with account_id from JWT sub, not client IP
        verify(rateLimiter).isAllowed("refresh", "account-42");
    }

    // -----------------------------------------------------------------------
    // Micrometer counter tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("scope 허용 + global 허용 시 scope=login/result=allowed, scope=global/result=allowed 카운터 증가")
    void counter_scopeAllowed_globalAllowed_incrementsBoth() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/auth/login")).willReturn("login");
        given(rateLimiter.isAllowed(eq("login"), anyString()))
                .willReturn(Mono.just(RateLimitResult.allowed()));
        given(rateLimiter.isAllowed(eq("global"), anyString()))
                .willReturn(Mono.just(RateLimitResult.allowed()));
        given(chain.filter(any())).willReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(counterValue("login", "allowed")).isEqualTo(1.0);
        assertThat(counterValue("global", "allowed")).isEqualTo(1.0);
        assertThat(counterValue("login", "rejected")).isZero();
        assertThat(counterValue("global", "rejected")).isZero();
    }

    @Test
    @DisplayName("scope 거부 시 scope=login/result=rejected 카운터 증가, global 카운터 미증가")
    void counter_scopeRejected_incrementsScopeRejected_skipsGlobal() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/auth/login")).willReturn("login");
        given(rateLimiter.isAllowed(eq("login"), anyString()))
                .willReturn(Mono.just(RateLimitResult.rejected(60)));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(counterValue("login", "rejected")).isEqualTo(1.0);
        assertThat(counterValue("login", "allowed")).isZero();
        assertThat(counterValue("global", "allowed")).isZero();
        assertThat(counterValue("global", "rejected")).isZero();
    }

    @Test
    @DisplayName("scope 허용 후 global 거부 시 scope=login/result=allowed, scope=global/result=rejected 카운터 증가")
    void counter_scopeAllowed_globalRejected_incrementsCorrectly() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/auth/login")).willReturn("login");
        given(rateLimiter.isAllowed(eq("login"), anyString()))
                .willReturn(Mono.just(RateLimitResult.allowed()));
        given(rateLimiter.isAllowed(eq("global"), anyString()))
                .willReturn(Mono.just(RateLimitResult.rejected(30)));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(counterValue("login", "allowed")).isEqualTo(1.0);
        assertThat(counterValue("global", "rejected")).isEqualTo(1.0);
        assertThat(counterValue("login", "rejected")).isZero();
        assertThat(counterValue("global", "allowed")).isZero();
    }

    @Test
    @DisplayName("scope=null(non-rate-limited path): scope 카운터 없이 global 카운터만 증가")
    void counter_nullScope_onlyGlobalCounterFires() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/accounts/me")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/accounts/me")).willReturn(null);
        given(rateLimiter.isAllowed(eq("global"), anyString()))
                .willReturn(Mono.just(RateLimitResult.allowed()));
        given(chain.filter(any())).willReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(counterValue("global", "allowed")).isEqualTo(1.0);
        // No scope-specific counter registered at all
        assertThat(meterRegistry.find("gateway_ratelimit_total")
                .tag("scope", "login").counter()).isNull();
        assertThat(meterRegistry.find("gateway_ratelimit_total")
                .tag("scope", "signup").counter()).isNull();
    }

    @Test
    @DisplayName("scope=null + global 거부 시 global/result=rejected 카운터 증가")
    void counter_nullScope_globalRejected_incrementsGlobalRejected() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/accounts/me")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.resolveRateLimitScope("/api/accounts/me")).willReturn(null);
        given(rateLimiter.isAllowed(eq("global"), anyString()))
                .willReturn(Mono.just(RateLimitResult.rejected(1)));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(counterValue("global", "rejected")).isEqualTo(1.0);
        assertThat(counterValue("global", "allowed")).isZero();
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private double counterValue(String scope, String result) {
        Counter counter = meterRegistry.find("gateway_ratelimit_total")
                .tag("scope", scope)
                .tag("result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
