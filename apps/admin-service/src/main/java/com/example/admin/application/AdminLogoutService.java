package com.example.admin.application;

import com.example.admin.application.port.TokenBlacklistPort;
import com.example.admin.infrastructure.config.AdminJwtProperties;
import com.example.admin.infrastructure.persistence.AdminOperatorRefreshTokenJpaEntity;
import com.example.admin.infrastructure.persistence.AdminOperatorRefreshTokenJpaRepository;
import com.example.admin.infrastructure.persistence.rbac.AdminOperatorJpaRepository;
import com.gap.security.jwt.JwtVerificationException;
import com.gap.security.jwt.JwtVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * TASK-BE-040 — implements {@code POST /api/admin/auth/logout}.
 *
 * <p>The access JWT's jti is written to the Redis blacklist
 * ({@code admin:jti:blacklist:{jti}}, TTL = remaining access-token lifetime)
 * so {@link com.example.admin.infrastructure.security.OperatorAuthenticationFilter}
 * can reject any subsequent use of the same access token. If the caller
 * supplies a refresh token, its registry row is revoked with reason=LOGOUT
 * (best-effort — failure does not undo the blacklist write).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLogoutService {

    private final TokenBlacklistPort blacklist;
    private final AdminJwtProperties jwtProperties;
    private final JwtVerifier jwtVerifier;
    private final AdminOperatorRefreshTokenJpaRepository tokenRepository;
    private final AdminOperatorJpaRepository operatorRepository;

    @Transactional
    public void logout(String operatorUuid, String accessJti, Instant accessExp, String refreshTokenJwt) {
        if (accessJti == null) {
            // Defensive: filter must populate jti for /logout.
            log.warn("Logout invoked without access jti for operatorId={}", operatorUuid);
            return;
        }
        long ttlSeconds = computeTtlSeconds(accessExp);
        try {
            blacklist.blacklist(accessJti, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException ex) {
            // Surface as 500 — operator's intent to logout failed and they
            // should retry; the access token remains valid until its natural
            // expiry. Do not silently succeed.
            log.error("Failed to write logout blacklist key for jti={}", accessJti, ex);
            throw ex;
        }

        if (refreshTokenJwt != null && !refreshTokenJwt.isBlank()) {
            revokeRefreshToken(operatorUuid, refreshTokenJwt);
        }
    }

    private long computeTtlSeconds(Instant accessExp) {
        if (accessExp == null) {
            return jwtProperties.getAccessTokenTtlSeconds();
        }
        long secs = Duration.between(Instant.now(), accessExp).getSeconds();
        if (secs <= 0) return 1L; // already expired; still record briefly so concurrent checks see it
        return secs;
    }

    private void revokeRefreshToken(String operatorUuid, String refreshTokenJwt) {
        Map<String, Object> claims;
        try {
            claims = jwtVerifier.verify(refreshTokenJwt);
        } catch (JwtVerificationException ex) {
            log.debug("logout: refresh token failed verification — ignoring (operatorId={})", operatorUuid);
            return;
        }
        Object tokenType = claims.get("token_type");
        if (!jwtProperties.getRefreshTokenType().equals(tokenType)) return;
        Object jtiObj = claims.get("jti");
        if (jtiObj == null) return;
        String jti = jtiObj.toString();

        tokenRepository.findById(jti).ifPresent(row -> {
            // Sanity: ensure the refresh token belongs to the operator that
            // owns the access token. Mismatch is silently ignored to avoid
            // leaking presence of someone else's jti.
            operatorRepository.findByOperatorId(operatorUuid).ifPresent(op -> {
                if (op.getId().equals(row.getOperatorId()) && !row.isRevoked()) {
                    row.revoke(Instant.now(), AdminOperatorRefreshTokenJpaEntity.REASON_LOGOUT);
                    tokenRepository.save(row);
                }
            });
        });
    }
}
