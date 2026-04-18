package com.example.auth.infrastructure.oauth;

import com.example.auth.domain.oauth.OAuthProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrosoftOAuthClientTest {

    private static final String REDIRECT_URI = "http://localhost:3000/oauth/callback";

    private WireMockServer wireMockServer;
    private MicrosoftOAuthClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        OAuthProperties props = new OAuthProperties();
        props.getMicrosoft().setClientId("test-client-id");
        props.getMicrosoft().setClientSecret("test-client-secret");
        props.getMicrosoft().setTokenUri(wireMockServer.baseUrl() + "/oauth2/v2.0/token");

        client = new MicrosoftOAuthClient(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    @DisplayName("exchangeCodeForUserInfo returns OAuthUserInfo when id_token has sub, email, name")
    void happyPath() {
        String idToken = buildIdToken("{\"sub\":\"ms-user-123\",\"email\":\"alice@contoso.com\",\"name\":\"Alice\"}");
        stubTokenEndpoint(idToken);

        OAuthUserInfo info = client.exchangeCodeForUserInfo("auth-code", REDIRECT_URI);

        assertThat(info.providerUserId()).isEqualTo("ms-user-123");
        assertThat(info.email()).isEqualTo("alice@contoso.com");
        assertThat(info.name()).isEqualTo("Alice");
        assertThat(info.provider()).isEqualTo(OAuthProvider.MICROSOFT);
    }

    @Test
    @DisplayName("exchangeCodeForUserInfo falls back to preferred_username when email is missing")
    void emailFallbackToPreferredUsername() {
        String idToken = buildIdToken(
                "{\"sub\":\"ms-user-456\",\"preferred_username\":\"bob@fabrikam.com\",\"name\":\"Bob\"}");
        stubTokenEndpoint(idToken);

        OAuthUserInfo info = client.exchangeCodeForUserInfo("auth-code", REDIRECT_URI);

        assertThat(info.email()).isEqualTo("bob@fabrikam.com");
    }

    @Test
    @DisplayName("exchangeCodeForUserInfo returns null email when both email and preferred_username are missing")
    void emailNullWhenBothMissing() {
        String idToken = buildIdToken("{\"sub\":\"ms-user-789\",\"name\":\"Carol\"}");
        stubTokenEndpoint(idToken);

        OAuthUserInfo info = client.exchangeCodeForUserInfo("auth-code", REDIRECT_URI);

        assertThat(info.email()).isNull();
        assertThat(info.providerUserId()).isEqualTo("ms-user-789");
    }

    @Test
    @DisplayName("exchangeCodeForUserInfo ignores preferred_username without @ (non-email UPN)")
    void preferredUsernameWithoutAtIsIgnored() {
        String idToken = buildIdToken(
                "{\"sub\":\"ms-user-000\",\"preferred_username\":\"external-user#EXT\",\"name\":\"Dave\"}");
        stubTokenEndpoint(idToken);

        OAuthUserInfo info = client.exchangeCodeForUserInfo("auth-code", REDIRECT_URI);

        assertThat(info.email()).isNull();
    }

    @Test
    @DisplayName("exchangeCodeForUserInfo throws OAuthProviderException when id_token missing")
    void tokenResponseMissingIdToken() {
        wireMockServer.stubFor(post(urlEqualTo("/oauth2/v2.0/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"abc\"}")));

        assertThatThrownBy(() -> client.exchangeCodeForUserInfo("auth-code", REDIRECT_URI))
                .isInstanceOf(OAuthProviderException.class)
                .hasMessageContaining("missing id_token");
    }

    @Test
    @DisplayName("exchangeCodeForUserInfo throws OAuthProviderException when id_token lacks sub")
    void idTokenMissingSub() {
        String idToken = buildIdToken("{\"email\":\"x@y.com\"}");
        stubTokenEndpoint(idToken);

        assertThatThrownBy(() -> client.exchangeCodeForUserInfo("auth-code", REDIRECT_URI))
                .isInstanceOf(OAuthProviderException.class)
                .hasMessageContaining("missing 'sub'");
    }

    @Test
    @DisplayName("exchangeCodeForUserInfo wraps token endpoint 5xx into OAuthProviderException")
    void tokenEndpoint5xx() {
        wireMockServer.stubFor(post(urlEqualTo("/oauth2/v2.0/token"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.exchangeCodeForUserInfo("auth-code", REDIRECT_URI))
                .isInstanceOf(OAuthProviderException.class)
                .hasMessageContaining("Microsoft OAuth provider error");
    }

    @Test
    @DisplayName("exchangeCodeForUserInfo throws OAuthProviderException when id_token is malformed (no dot)")
    void malformedIdToken() {
        wireMockServer.stubFor(post(urlEqualTo("/oauth2/v2.0/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id_token\":\"notajwt\"}")));

        assertThatThrownBy(() -> client.exchangeCodeForUserInfo("auth-code", REDIRECT_URI))
                .isInstanceOf(OAuthProviderException.class)
                .hasMessageContaining("Malformed Microsoft id_token");
    }

    private void stubTokenEndpoint(String idToken) {
        wireMockServer.stubFor(post(urlEqualTo("/oauth2/v2.0/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id_token\":\"" + idToken + "\",\"access_token\":\"ms-access-token\"}")));
    }

    private static String buildIdToken(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        return header + "." + payload + ".sig";
    }
}
