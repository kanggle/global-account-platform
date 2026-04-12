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
import java.util.Base64;

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
            case "refresh" -> extractAccountIdFromJwt(request, clientIp);
            default -> clientIp;
        };
    }

    /**
     * Extracts account_id from JWT sub claim for refresh scope rate limiting.
     * Falls back to client IP if Authorization header is absent or JWT is unparseable.
     * Only decodes the payload to read the sub claim -- does NOT verify the signature
     * (signature verification is handled by JwtAuthenticationFilter).
     */
    private String extractAccountIdFromJwt(ServerHttpRequest request, String clientIp) {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("refresh rate-limit: Authorization header absent, falling back to IP={}", clientIp);
            return clientIp;
        }

        String token = authHeader.substring(7);
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                log.warn("refresh rate-limit: malformed JWT (less than 2 parts), falling back to IP={}", clientIp);
                return clientIp;
            }
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            // Minimal JSON parsing for sub claim without pulling in full JWT library
            com.fasterxml.jackson.databind.JsonNode payload =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);
            com.fasterxml.jackson.databind.JsonNode sub = payload.get("sub");
            if (sub != null && !sub.asText().isBlank()) {
                return sub.asText();
            }
            log.warn("refresh rate-limit: JWT has no sub claim, falling back to IP={}", clientIp);
            return clientIp;
        } catch (Exception e) {
            log.warn("refresh rate-limit: failed to parse JWT for account_id, falling back to IP={}", clientIp, e);
            return clientIp;
        }
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
