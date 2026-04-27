package com.example.auth.infrastructure.oauth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "oauth")
public class OAuthProperties {

    private ProviderProperties google = new ProviderProperties();
    private ProviderProperties kakao = new ProviderProperties();
    private ProviderProperties microsoft = new ProviderProperties();

    @Getter
    @Setter
    public static class ProviderProperties {
        private String clientId;
        private String clientSecret;
        private String redirectUri = "http://localhost:3000/oauth/callback";
        /**
         * Server-side allowlist of redirect URIs accepted from the client. If empty,
         * {@link #redirectUri} is used as the single allowed value (backward
         * compatibility). Comparison is exact-string match — no normalization, no
         * prefix matching, no wildcard support, to prevent open-redirect bypasses.
         */
        private List<String> allowedRedirectUris = new ArrayList<>();
        private String scopes;
        private String tokenUri;
        private String authUri;
        private String userInfoUri;

        public List<String> resolveAllowedRedirectUris() {
            if (allowedRedirectUris != null && !allowedRedirectUris.isEmpty()) {
                return List.copyOf(allowedRedirectUris);
            }
            return redirectUri != null ? List.of(redirectUri) : List.of();
        }
    }
}
