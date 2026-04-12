package com.example.gateway.filter;

import com.example.gateway.ratelimit.TokenBucketRateLimiter;
import com.example.gateway.ratelimit.TokenBucketRateLimiter.RateLimitResult;
import com.example.gateway.route.RouteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter 단위 테스트")
class RateLimitFilterTest {

    @Mock
    private TokenBucketRateLimiter rateLimiter;

    @Mock
    private RouteConfig routeConfig;

    @Mock
    private GatewayFilterChain chain;

    private RateLimitFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new RateLimitFilter(rateLimiter, routeConfig, objectMapper);
    }

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
}
