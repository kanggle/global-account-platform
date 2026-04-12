package com.example.admin.infrastructure.config;

import com.gap.security.jwt.JwtVerifier;
import com.gap.security.jwt.Rs256JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
public class JwtConfig {

    @Value("${admin.jwt.public-key-path}")
    private Resource publicKeyResource;

    @Bean
    public PublicKey operatorPublicKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] keyBytes = readPemKey(publicKeyResource);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }

    @Bean
    public JwtVerifier operatorJwtVerifier(PublicKey operatorPublicKey) {
        return new Rs256JwtVerifier(operatorPublicKey);
    }

    private byte[] readPemKey(Resource resource) throws IOException {
        try (InputStream is = resource.getInputStream()) {
            String pem = new String(is.readAllBytes());
            pem = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            return Base64.getDecoder().decode(pem);
        }
    }
}
