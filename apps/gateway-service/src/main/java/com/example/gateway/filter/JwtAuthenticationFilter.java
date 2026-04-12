package com.example.gateway.filter;

import com.example.gateway.route.RouteConfig;
import com.example.gateway.security.TokenValidator;
import com.example.web.dto.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gap.security.jwt.JwtVerificationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JWT authentication filter for protected routes.
 * Validates RS256 JWT tokens, checks exp/nbf claims,
 * strips spoofed X-Account-ID headers, and injects verified X-Account-ID.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -100;
    private static final String ACCOUNT_ID_HEADER = "X-Account-ID";

    private final TokenValidator tokenValidator;
    private final RouteConfig routeConfig;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(TokenValidator tokenValidator,
                                   RouteConfig routeConfig,
                                   ObjectMapper objectMapper) {
        this.tokenValidator = tokenValidator;
        this.routeConfig = routeConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 1. Always strip spoofed headers
        ServerHttpRequest stripped = stripSpoofedHeaders(request);
        ServerWebExchange strippedExchange = exchange.mutate().request(stripped).build();

        // 2. Public routes pass through without auth
        if (routeConfig.isPublicRoute(request.getMethod(), path)) {
            return chain.filter(strippedExchange);
        }

        // 3. Extract Authorization header
        String authHeader = stripped.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeUnauthorized(exchange,
                    "Access token is missing, expired, or has an invalid signature");
        }

        String token = authHeader.substring(7);

        // 4. Validate token and enrich request
        return tokenValidator.validate(token)
                .flatMap(claims -> {
                    String accountId = extractAccountId(claims);
                    if (accountId == null) {
                        return writeUnauthorized(exchange,
                                "Access token is missing, expired, or has an invalid signature");
                    }

                    ServerHttpRequest enriched = stripped.mutate()
                            .header(ACCOUNT_ID_HEADER, accountId)
                            .build();

                    return chain.filter(strippedExchange.mutate().request(enriched).build());
                })
                .onErrorResume(JwtVerificationException.class, e -> {
                    log.debug("JWT verification failed: {}", e.getMessage());
                    return writeUnauthorized(exchange,
                            "Access token is missing, expired, or has an invalid signature");
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("Unexpected error during JWT verification: {}", e.getMessage(), e);
                    return writeUnauthorized(exchange,
                            "Access token is missing, expired, or has an invalid signature");
                });
    }

    private ServerHttpRequest stripSpoofedHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(h -> {
                    h.remove(ACCOUNT_ID_HEADER);
                })
                .build();
    }

    private String extractAccountId(Map<String, Object> claims) {
        Object sub = claims.get("sub");
        if (sub != null) {
            return sub.toString();
        }
        Object accountId = claims.get("accountId");
        if (accountId != null) {
            return accountId.toString();
        }
        return null;
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ErrorResponse errorResponse = ErrorResponse.of("TOKEN_INVALID", message);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return response.writeWith(Mono.just(
                    response.bufferFactory().wrap(
                            "{\"code\":\"TOKEN_INVALID\",\"message\":\"Access token is missing, expired, or has an invalid signature\"}"
                                    .getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
