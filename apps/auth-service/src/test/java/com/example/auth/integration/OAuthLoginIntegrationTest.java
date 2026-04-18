package com.example.auth.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for OAuth social login end-to-end flow across Google / Kakao / Microsoft.
 *
 * <p>TASK-BE-057: covers the gap left by TASK-BE-053 / TASK-BE-056 — verifies the full
 * callback pipeline (token exchange → social_identities write → device session → JWT
 * issuance → outbox event with loginMethod) against real MySQL / Redis / Kafka via
 * Testcontainers, with WireMock standing in for account-service and each OAuth provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class OAuthLoginIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("auth_db")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static WireMockServer wireMock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(18082);
        wireMock.start();
        WireMock.configureFor("localhost", 18082);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("auth.account-service.base-url", () -> "http://localhost:18082");

        // Redirect OAuth providers to WireMock
        registry.add("oauth.google.token-uri", () -> "http://localhost:18082/google/token");
        registry.add("oauth.google.auth-uri", () -> "http://localhost:18082/google/authorize");
        registry.add("oauth.kakao.token-uri", () -> "http://localhost:18082/kakao/token");
        registry.add("oauth.kakao.auth-uri", () -> "http://localhost:18082/kakao/authorize");
        registry.add("oauth.kakao.user-info-uri", () -> "http://localhost:18082/kakao/userinfo");
        registry.add("oauth.microsoft.token-uri", () -> "http://localhost:18082/microsoft/token");
        registry.add("oauth.microsoft.auth-uri", () -> "http://localhost:18082/microsoft/authorize");
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();

        // Clear Redis between tests
        var keys = redisTemplate.keys("oauth:state:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // Clear social_identities / outbox / refresh_tokens so assertions are deterministic
        jdbcTemplate.update("DELETE FROM social_identities");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM device_sessions");
    }

    // ----------------------------------------------------------------------
    // Happy-path tests per provider
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("Google: authorize + callback → tokens, social_identities row, outbox OAUTH_GOOGLE")
    void googleHappyPath() throws Exception {
        String state = performAuthorize("google");

        stubGoogleTokenEndpoint("google-sub-001", "alice.google@example.com", "Alice G");
        stubSocialSignup("acc-google-001", true);
        stubCredentialLookup("alice.google@example.com", "acc-google-001", "ACTIVE");

        performCallback("google", "auth-code-g", state)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.isNewAccount").value(true));

        assertSocialIdentity("GOOGLE", "google-sub-001", "acc-google-001");
        assertOutboxLoginMethod("acc-google-001", "OAUTH_GOOGLE");
    }

    @Test
    @DisplayName("Kakao: authorize + callback (access_token + userinfo) → outbox OAUTH_KAKAO")
    void kakaoHappyPath() throws Exception {
        String state = performAuthorize("kakao");

        wireMock.stubFor(WireMock.post(urlEqualTo("/kakao/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"kakao-access-token\"}")));

        wireMock.stubFor(WireMock.get(urlEqualTo("/kakao/userinfo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": 987654321,
                                  "kakao_account": {
                                    "email": "bob.kakao@example.com",
                                    "profile": { "nickname": "Bob K" }
                                  }
                                }
                                """)));

        stubSocialSignup("acc-kakao-002", true);
        stubCredentialLookup("bob.kakao@example.com", "acc-kakao-002", "ACTIVE");

        performCallback("kakao", "auth-code-k", state)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewAccount").value(true));

        assertSocialIdentity("KAKAO", "987654321", "acc-kakao-002");
        assertOutboxLoginMethod("acc-kakao-002", "OAUTH_KAKAO");
    }

    @Test
    @DisplayName("Microsoft: authorize + callback (id_token sub/email) → outbox OAUTH_MICROSOFT")
    void microsoftHappyPath() throws Exception {
        String state = performAuthorize("microsoft");

        stubMicrosoftTokenEndpoint("ms-sub-003", "carol@contoso.com", null, "Carol M");
        stubSocialSignup("acc-ms-003", true);
        stubCredentialLookup("carol@contoso.com", "acc-ms-003", "ACTIVE");

        performCallback("microsoft", "auth-code-m", state)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewAccount").value(true));

        assertSocialIdentity("MICROSOFT", "ms-sub-003", "acc-ms-003");
        assertOutboxLoginMethod("acc-ms-003", "OAUTH_MICROSOFT");
    }

    @Test
    @DisplayName("Microsoft: email absent → preferred_username fallback is used as email")
    void microsoftPreferredUsernameFallback() throws Exception {
        String state = performAuthorize("microsoft");

        stubMicrosoftTokenEndpoint("ms-sub-004", null, "dave@fabrikam.com", "Dave M");
        stubSocialSignup("acc-ms-004", true);
        stubCredentialLookup("dave@fabrikam.com", "acc-ms-004", "ACTIVE");

        performCallback("microsoft", "auth-code-m4", state)
                .andExpect(status().isOk());

        String providerEmail = jdbcTemplate.queryForObject(
                "SELECT provider_email FROM social_identities WHERE provider = 'MICROSOFT' AND provider_user_id = 'ms-sub-004'",
                String.class);
        assertThat(providerEmail).isEqualTo("dave@fabrikam.com");
    }

    // ----------------------------------------------------------------------
    // Existing-email auto-link scenario
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("Microsoft: existing email → isNewAccount false, social_identities created on existing account")
    void microsoftExistingEmailAutoLink() throws Exception {
        String state = performAuthorize("microsoft");

        stubMicrosoftTokenEndpoint("ms-sub-005", "erin@contoso.com", null, "Erin M");
        // account-service reports the email already exists on an account
        stubSocialSignup("acc-existing-999", false);
        stubCredentialLookup("erin@contoso.com", "acc-existing-999", "ACTIVE");

        performCallback("microsoft", "auth-code-m5", state)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewAccount").value(false));

        assertSocialIdentity("MICROSOFT", "ms-sub-005", "acc-existing-999");
    }

    // ----------------------------------------------------------------------
    // Failure scenarios
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("State missing from Redis → 401 INVALID_STATE")
    void stateExpired() throws Exception {
        String state = performAuthorize("google");
        // Simulate expiry by deleting the Redis key
        redisTemplate.delete("oauth:state:" + state);

        performCallback("google", "auth-code", state)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    @DisplayName("Microsoft token endpoint 5xx → 502 PROVIDER_ERROR")
    void microsoftProvider5xx() throws Exception {
        String state = performAuthorize("microsoft");

        wireMock.stubFor(WireMock.post(urlEqualTo("/microsoft/token"))
                .willReturn(aResponse().withStatus(500)));

        performCallback("microsoft", "auth-code-500", state)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVIDER_ERROR"));
    }

    // ----------------------------------------------------------------------
    // Shared helpers
    // ----------------------------------------------------------------------

    private String performAuthorize(String provider) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/oauth/authorize")
                        .param("provider", provider)
                        .param("redirectUri", "http://localhost:3000/oauth/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String state = body.path("state").asText();
        assertThat(redisTemplate.hasKey("oauth:state:" + state)).isTrue();
        return state;
    }

    private org.springframework.test.web.servlet.ResultActions performCallback(
            String provider, String code, String state) throws Exception {
        String body = """
                {
                  "provider": "%s",
                  "code": "%s",
                  "state": "%s",
                  "redirectUri": "http://localhost:3000/oauth/callback"
                }
                """.formatted(provider, code, state);
        return mockMvc.perform(post("/api/auth/oauth/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Fingerprint", "fp-integration-" + provider)
                .header("User-Agent", "integration-test/1.0")
                .header("X-Geo-Country", "KR")
                .content(body));
    }

    private void stubGoogleTokenEndpoint(String sub, String email, String name) {
        String payload = "{\"sub\":\"" + sub + "\",\"email\":\"" + email + "\",\"name\":\"" + name + "\"}";
        String idToken = buildFakeJwt(payload);
        wireMock.stubFor(WireMock.post(urlEqualTo("/google/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id_token\":\"" + idToken + "\"}")));
    }

    private void stubMicrosoftTokenEndpoint(String sub, String email, String preferredUsername, String name) {
        StringBuilder payload = new StringBuilder("{\"sub\":\"").append(sub).append("\"");
        if (email != null) {
            payload.append(",\"email\":\"").append(email).append("\"");
        }
        if (preferredUsername != null) {
            payload.append(",\"preferred_username\":\"").append(preferredUsername).append("\"");
        }
        if (name != null) {
            payload.append(",\"name\":\"").append(name).append("\"");
        }
        payload.append("}");
        String idToken = buildFakeJwt(payload.toString());
        wireMock.stubFor(WireMock.post(urlEqualTo("/microsoft/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id_token\":\"" + idToken + "\"}")));
    }

    private void stubSocialSignup(String accountId, boolean newAccount) {
        wireMock.stubFor(WireMock.post(urlEqualTo("/internal/accounts/social-signup"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "accountId": "%s",
                                  "accountStatus": "ACTIVE",
                                  "newAccount": %s
                                }
                                """.formatted(accountId, newAccount))));
    }

    private void stubCredentialLookup(String email, String accountId, String status) {
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/internal/accounts/credentials"))
                .withQueryParam("email", equalTo(email))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "accountId": "%s",
                                  "credentialHash": "dummy",
                                  "hashAlgorithm": "argon2id",
                                  "accountStatus": "%s"
                                }
                                """.formatted(accountId, status))));
    }

    private void assertSocialIdentity(String provider, String providerUserId, String expectedAccountId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT account_id FROM social_identities WHERE provider = ? AND provider_user_id = ?",
                provider, providerUserId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("account_id")).isEqualTo(expectedAccountId);
    }

    private void assertOutboxLoginMethod(String accountId, String expectedLoginMethod) throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox WHERE aggregate_id = ? AND event_type = 'auth.login.succeeded'",
                accountId);
        assertThat(rows)
                .as("outbox auth.login.succeeded row for account " + accountId)
                .isNotEmpty();
        String payload = (String) rows.get(0).get("payload");
        JsonNode parsed = objectMapper.readTree(payload);
        assertThat(parsed.path("loginMethod").asText()).isEqualTo(expectedLoginMethod);
    }

    private static String buildFakeJwt(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        return header + "." + payload + ".sig";
    }

    @SuppressWarnings("unused")
    private static String nowIso() {
        return Instant.now().toString();
    }
}
