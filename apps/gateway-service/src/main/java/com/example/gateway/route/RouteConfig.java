package com.example.gateway.route;

import com.example.gateway.config.GatewayProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.HashSet;
import java.util.Set;

/**
 * Manages public route resolution and path-to-scope mapping for rate limiting.
 */
@Component
public class RouteConfig {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final Set<String> publicRoutes;

    public RouteConfig(GatewayProperties properties) {
        this.publicRoutes = new HashSet<>(properties.getPublicPaths());
    }

    /**
     * Checks if the given method + path combination is a public route (no auth required).
     */
    public boolean isPublicRoute(HttpMethod method, String path) {
        if (method == null) {
            return false;
        }
        String key = method.name() + ":" + path;
        for (String publicRoute : publicRoutes) {
            String[] parts = publicRoute.split(":", 2);
            if (parts.length == 2) {
                if (parts[0].equals(method.name()) && PATH_MATCHER.match(parts[1], path)) {
                    return true;
                }
            }
        }
        // actuator health is always public regardless of method
        if ("/actuator/health".equals(path)) {
            return true;
        }
        return false;
    }

    /**
     * Resolves the rate limit scope for the given path.
     * Returns null if no specific scope applies (only global scope).
     */
    public String resolveRateLimitScope(String path) {
        if (path.startsWith("/api/auth/login")) {
            return "login";
        }
        if (path.startsWith("/api/accounts/signup")) {
            return "signup";
        }
        if (path.startsWith("/api/auth/refresh")) {
            return "refresh";
        }
        return null;
    }
}
