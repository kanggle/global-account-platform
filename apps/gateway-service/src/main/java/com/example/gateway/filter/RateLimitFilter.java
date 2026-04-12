package com.example.gateway.filter;

import com.example.gateway.ratelimit.TokenBucketRateLimiter;
import com.example.gateway.ratelimit.TokenBucketRateLimiter.RateLimitResult;
import com.example.gateway.route.RouteConfig;
import com.example.web.dto.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Global rate limit filter using Redis token bucket.
 * Applies scope-specific limits (login, signup, refresh) and global IP-based limit.
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -150;

    private final TokenBucketRateLimiter rateLimiter;
    private final RouteConfig routeConfig;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(TokenBucketRateLimiter rateLimiter,
                           RouteConfig routeConfig,
                           ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.routeConfig = routeConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String clientIp = resolveClientIp(request);

        // Check scope-specific rate limit first
        String scope = routeConfig.resolveRateLimitScope(path);

        Mono<RateLimitResult> scopeCheck;
        if (scope != null) {
            String identifier = resolveIdentifier(scope, clientIp, request);
            scopeCheck = rateLimiter.isAllowed(scope, identifier);
        } else {
            scopeCheck = Mono.just(RateLimitResult.allowed());
        }

        return scopeCheck.flatMap(scopeResult -> {
            if (!scopeResult.isAllowed()) {
                return writeRateLimitResponse(exchange, scopeResult.retryAfterSeconds());
            }
            // Always check global rate limit
            return rateLimiter.isAllowed("global", clientIp)
                    .flatMap(globalResult -> {
                        if (!globalResult.isAllowed()) {
                            return writeRateLimitResponse(exchange, globalResult.retryAfterSeconds());
                        }
                        return chain.filter(exchange);
                    });
        });
    }

    private String resolveClientIp(ServerHttpRequest request) {
        // Check X-Forwarded-For first
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null) {
            InetAddress address = remoteAddress.getAddress();
            if (address != null) {
                return address.getHostAddress();
            }
        }
        return "unknown";
    }

    private String resolveIdentifier(String scope, String clientIp, ServerHttpRequest request) {
        return switch (scope) {
            case "login" -> extractSubnet(clientIp);
            case "signup" -> clientIp;
            case "refresh" -> {
                // Try to extract account_id from JWT for refresh scope
                String authHeader = request.getHeaders().getFirst("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    yield clientIp; // Simplified: use IP since token may not be decoded yet
                }
                yield clientIp;
            }
            default -> clientIp;
        };
    }

    /**
     * Extracts /24 subnet from IP address for login rate limiting.
     */
    private String extractSubnet(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + "." + parts[2] + ".0/24";
        }
        return ip;
    }

    private Mono<Void> writeRateLimitResponse(ServerWebExchange exchange, long retryAfterSeconds) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));

        ErrorResponse errorResponse = ErrorResponse.of("RATE_LIMITED",
                "Too many requests. Try again later.");
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return response.writeWith(Mono.just(
                    response.bufferFactory().wrap(
                            "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}"
                                    .getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
