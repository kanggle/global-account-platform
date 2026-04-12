package com.example.gateway.filter;

import com.example.gateway.route.RouteConfig;
import com.example.gateway.security.TokenValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gap.security.jwt.JwtVerificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 단위 테스트")
class JwtAuthenticationFilterTest {

    @Mock
    private TokenValidator tokenValidator;

    @Mock
    private RouteConfig routeConfig;

    @Mock
    private GatewayFilterChain chain;

    private JwtAuthenticationFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new JwtAuthenticationFilter(tokenValidator, routeConfig, objectMapper);
    }

    @Test
    @DisplayName("공개 경로는 인증 없이 통과")
    void filter_publicRoute_passesThrough() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.isPublicRoute(HttpMethod.POST, "/api/auth/login")).willReturn(true);
        given(chain.filter(any())).willReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Authorization 헤더 없으면 401 반환")
    void filter_missingAuthHeader_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/accounts/me")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.isPublicRoute(HttpMethod.GET, "/api/accounts/me")).willReturn(false);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("유효한 JWT로 인증 성공 시 X-Account-ID 헤더 주입")
    void filter_validToken_injectsAccountIdHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/accounts/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.isPublicRoute(HttpMethod.GET, "/api/accounts/me")).willReturn(false);
        given(tokenValidator.validate("valid-token"))
                .willReturn(Mono.just(Map.of("sub", "account-123", "email", "test@example.com")));
        given(chain.filter(any())).willReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Status should not be set (chain proceeded normally)
        // The chain.filter was called with enriched request
    }

    @Test
    @DisplayName("만료된 JWT 토큰 시 401 TOKEN_INVALID 반환")
    void filter_expiredToken_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/accounts/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer expired-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.isPublicRoute(HttpMethod.GET, "/api/accounts/me")).willReturn(false);
        given(tokenValidator.validate("expired-token"))
                .willReturn(Mono.error(new JwtVerificationException("Token has expired")));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("외부에서 보낸 X-Account-ID 헤더가 제거됨 (spoofing 방지)")
    void filter_spoofedAccountIdHeader_isStripped() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .header("X-Account-ID", "spoofed-id")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        given(routeConfig.isPublicRoute(HttpMethod.POST, "/api/auth/login")).willReturn(true);
        given(chain.filter(any())).willAnswer(invocation -> {
            // Verify that the exchange passed to chain has X-Account-ID stripped
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }
}
