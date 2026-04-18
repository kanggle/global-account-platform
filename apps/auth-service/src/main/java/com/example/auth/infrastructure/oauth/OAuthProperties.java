package com.example.auth.infrastructure.oauth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
        private String scopes;
        private String tokenUri;
        private String authUri;
        private String userInfoUri;
    }
}
