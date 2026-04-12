package com.example.account.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that validates internal API requests by checking the X-Internal-Token header.
 * Applied to /internal/** paths only.
 */
public class InternalApiFilter extends OncePerRequestFilter {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private final String expectedToken;

    public InternalApiFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/internal/")) {
            // When a token is configured, enforce it. When not configured, skip (dev/test mode).
            if (expectedToken != null && !expectedToken.isBlank()) {
                String token = request.getHeader(INTERNAL_TOKEN_HEADER);
                if (!expectedToken.equals(token)) {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid internal token\"}");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
