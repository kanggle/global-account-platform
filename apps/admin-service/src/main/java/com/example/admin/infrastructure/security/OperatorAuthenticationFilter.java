package com.example.admin.infrastructure.security;

import com.example.admin.application.OperatorContext;
import com.example.admin.application.OperatorRole;
import com.gap.security.jwt.JwtVerificationException;
import com.gap.security.jwt.JwtVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies the operator JWT on {@code /api/admin/**} requests:
 *   - RS256 signature via shared {@link JwtVerifier}
 *   - required claim {@code scope == "admin"}
 *   - extracts {@code sub} (operator id) and {@code roles} (list of operator roles)
 *
 * Populates an {@link OperatorAuthenticationToken} in the SecurityContext for
 * {@code @PreAuthorize} and for {@code OperatorContextHolder} resolution.
 */
@Slf4j
public class OperatorAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtVerifier jwtVerifier;
    private final String expectedScope;

    public OperatorAuthenticationFilter(JwtVerifier jwtVerifier, String expectedScope) {
        this.jwtVerifier = jwtVerifier;
        this.expectedScope = expectedScope;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            unauthorized(response, "TOKEN_INVALID", "Missing or malformed Authorization header");
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();

        Map<String, Object> claims;
        try {
            claims = jwtVerifier.verify(token);
        } catch (JwtVerificationException e) {
            unauthorized(response, "TOKEN_INVALID", "Operator token invalid");
            return;
        }

        // TODO(TASK-BE-028b): switch claim from "scope" to "token_type" and drop "roles"
        //                    claim (permissions resolved via DB per rbac.md D5).
        Object scope = claims.get("scope");
        if (!expectedScope.equals(scope)) {
            unauthorized(response, "TOKEN_INVALID", "Operator scope missing");
            return;
        }

        Object subObj = claims.get("sub");
        if (subObj == null) {
            unauthorized(response, "TOKEN_INVALID", "Operator subject missing");
            return;
        }

        Set<OperatorRole> roles = extractRoles(claims);
        if (roles.isEmpty()) {
            unauthorized(response, "TOKEN_INVALID", "Operator roles missing");
            return;
        }

        OperatorContext ctx = new OperatorContext(subObj.toString(), roles);
        Collection<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                .collect(Collectors.toSet());
        OperatorAuthenticationToken auth = new OperatorAuthenticationToken(ctx, authorities);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Set<OperatorRole> extractRoles(Map<String, Object> claims) {
        Object raw = claims.get("roles");
        if (raw == null) {
            raw = claims.get("role");
        }
        Set<OperatorRole> result = EnumSet.noneOf(OperatorRole.class);
        if (raw instanceof Collection<?> c) {
            for (Object v : c) {
                OperatorRole role = OperatorRole.fromString(String.valueOf(v));
                if (role != null) result.add(role);
            }
        } else if (raw instanceof String s) {
            for (String part : s.split("[,\\s]+")) {
                OperatorRole role = OperatorRole.fromString(part);
                if (role != null) result.add(role);
            }
        }
        return result;
    }

    private static void unauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message
                        + "\",\"timestamp\":\"" + Instant.now().toString() + "\"}");
    }

    public static class OperatorAuthenticationToken extends AbstractAuthenticationToken {
        private final OperatorContext principal;

        public OperatorAuthenticationToken(OperatorContext principal,
                                           Collection<? extends org.springframework.security.core.GrantedAuthority> authorities) {
            super(authorities == null ? List.of() : authorities);
            this.principal = principal;
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public OperatorContext getPrincipal() {
            return principal;
        }
    }
}
