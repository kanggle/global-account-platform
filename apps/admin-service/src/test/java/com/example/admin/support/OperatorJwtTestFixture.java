package com.example.admin.support;

import com.gap.security.jwt.JwtVerifier;
import com.gap.security.jwt.Rs256JwtSigner;
import com.gap.security.jwt.Rs256JwtVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight helper that generates an RSA key pair in-memory and mints
 * operator JWTs for slice/integration tests. Pairs with
 * {@link Rs256JwtVerifier} exposed via the {@link #verifier()} accessor so
 * tests can replace the production {@code operatorJwtVerifier} bean.
 */
public final class OperatorJwtTestFixture {

    private final KeyPair keyPair;
    private final Rs256JwtSigner signer;
    private final Rs256JwtVerifier verifier;

    public OperatorJwtTestFixture() {
        this.keyPair = generateKeyPair();
        this.signer = new Rs256JwtSigner(keyPair.getPrivate(), "test-key-001");
        this.verifier = new Rs256JwtVerifier(keyPair.getPublic());
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public JwtVerifier verifier() {
        return verifier;
    }

    public String operatorToken(String sub, List<String> roles) {
        return operatorToken(sub, roles, "admin");
    }

    public String operatorToken(String sub, List<String> roles, String scope) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", sub);
        claims.put("scope", scope);
        claims.put("roles", roles);
        claims.put("iss", "auth-service");
        claims.put("iat", now);
        claims.put("exp", now.plus(30, ChronoUnit.MINUTES));
        return signer.sign(claims);
    }

    public String expiredToken(String sub, List<String> roles) {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", sub);
        claims.put("scope", "admin");
        claims.put("roles", roles);
        claims.put("iss", "auth-service");
        claims.put("iat", past);
        claims.put("exp", past.plus(1, ChronoUnit.MINUTES));
        return signer.sign(claims);
    }
}
