package com.example.auth.infrastructure.oauth;

import com.example.auth.domain.oauth.OAuthProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthClientFactoryTest {

    private GoogleOAuthClient googleClient;
    private KakaoOAuthClient kakaoClient;
    private MicrosoftOAuthClient microsoftClient;
    private OAuthClientFactory factory;

    @BeforeEach
    void setUp() {
        OAuthProperties props = new OAuthProperties();
        ObjectMapper mapper = new ObjectMapper();
        googleClient = new GoogleOAuthClient(props, mapper);
        kakaoClient = new KakaoOAuthClient(props, mapper);
        microsoftClient = new MicrosoftOAuthClient(props, mapper);
        factory = new OAuthClientFactory(googleClient, kakaoClient, microsoftClient);
    }

    @Test
    @DisplayName("getClient returns GoogleOAuthClient for GOOGLE")
    void getClientGoogle() {
        assertThat(factory.getClient(OAuthProvider.GOOGLE)).isSameAs(googleClient);
    }

    @Test
    @DisplayName("getClient returns KakaoOAuthClient for KAKAO")
    void getClientKakao() {
        assertThat(factory.getClient(OAuthProvider.KAKAO)).isSameAs(kakaoClient);
    }

    @Test
    @DisplayName("getClient returns MicrosoftOAuthClient for MICROSOFT")
    void getClientMicrosoft() {
        assertThat(factory.getClient(OAuthProvider.MICROSOFT)).isSameAs(microsoftClient);
    }
}
