package com.example.security.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Filter that enforces authentication on /internal/** endpoints.
 * Checks for X-Internal-Token header and validates it against the configured token.
 * Returns 403 PERMISSION_DENIED in the standard error format if missing or invalid.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAuthFilter implements Filter {

    private static final String INTERNAL_PATH_PREFIX = "/internal/";
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public InternalAuthFilter(
            @Value("${security-service.internal-token:}") String expectedToken,
            ObjectMapper objectMapper) {
        this.expectedToken = expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // Only apply to /internal/** paths
        if (!path.startsWith(INTERNAL_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // Skip if no token is configured (e.g., local dev)
        if (expectedToken == null || expectedToken.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String providedToken = httpRequest.getHeader(TOKEN_HEADER);
        if (providedToken == null || !expectedToken.equals(providedToken)) {
            log.warn("Unauthorized access attempt to internal endpoint: path={}, remoteAddr={}",
                    path, httpRequest.getRemoteAddr());
            writePermissionDenied((HttpServletResponse) response);
            return;
        }

        chain.doFilter(request, response);
    }

    private void writePermissionDenied(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "PERMISSION_DENIED");
        body.put("message", "Authentication required for internal endpoints");
        body.put("timestamp", Instant.now().toString());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
