package com.example.admin.infrastructure.config;

import com.example.admin.infrastructure.security.OperatorAuthenticationFilter;
import com.gap.security.jwt.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Instant;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public OperatorAuthenticationFilter operatorAuthenticationFilter(
            JwtVerifier operatorJwtVerifier,
            @Value("${admin.jwt.expected-scope:admin}") String expectedScope) {
        return new OperatorAuthenticationFilter(operatorJwtVerifier, expectedScope);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           OperatorAuthenticationFilter operatorFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(operatorFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/admin/**").authenticated()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, resp, e) -> {
                            resp.setStatus(HttpStatus.UNAUTHORIZED.value());
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            resp.getWriter().write(
                                    "{\"code\":\"TOKEN_INVALID\",\"message\":\"Authentication required\""
                                            + ",\"timestamp\":\"" + Instant.now().toString() + "\"}");
                        })
                        .accessDeniedHandler((req, resp, e) -> {
                            resp.setStatus(HttpStatus.FORBIDDEN.value());
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            resp.getWriter().write(
                                    "{\"code\":\"PERMISSION_DENIED\",\"message\":\"Operator role insufficient\""
                                            + ",\"timestamp\":\"" + Instant.now().toString() + "\"}");
                        })
                );

        return http.build();
    }
}
