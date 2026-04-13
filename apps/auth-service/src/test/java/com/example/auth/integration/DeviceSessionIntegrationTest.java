package com.example.auth.integration;

import com.example.auth.domain.session.DeviceSession;
import com.example.auth.domain.session.RevokeReason;
import com.example.auth.domain.repository.DeviceSessionRepository;
import com.example.messaging.outbox.OutboxJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gap.security.password.Argon2idPasswordHasher;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration for device/session management in auth-service.
 *
 * <p>Covers the Phase A closing scenarios from TASK-BE-026:
 * <ol>
 *   <li>Two logins from distinct user-agents surface as two sessions.
 *   <li>Exceeding {@code max-active-sessions} evicts the oldest and records the outbox event.
 *   <li>Revoking one session invalidates its refresh token.
 *   <li>Bulk revoke keeps only the current session.
 * </ol>
 *
 * <p>Docker-gated via {@code isDockerAvailable()}; skips gracefully on hosts without Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class DeviceSessionIntegrationTest {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DeviceSessionRepository deviceSessionRepository;
    @Autowired private OutboxJpaRepository outboxJpaRepository;

    private static final String TEST_EMAIL = "session-it@example.com";
    private static final String TEST_PASSWORD = "password-session-1";
    private static final String ACCOUNT_ID = "acc-device-session-it";

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(18088);
        wireMock.start();
        WireMock.configureFor("localhost", 18088);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("auth.account-service.base-url", () -> "http://localhost:18088");
        registry.add("auth.device-session.max-active-sessions", () -> "10");
    }

    @BeforeEach
    void stubAccountService() {
        wireMock.resetAll();
        String hash = new Argon2idPasswordHasher().hash(TEST_PASSWORD);
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/internal/accounts/credentials"))
                .withQueryParam("email", equalTo(TEST_EMAIL))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"accountId":"%s","credentialHash":"%s","hashAlgorithm":"argon2id","accountStatus":"ACTIVE"}
                                """.formatted(ACCOUNT_ID, hash))));

        // Clean slate for the account under test
        deviceSessionRepository.findActiveByAccountId(ACCOUNT_ID)
                .forEach(s -> {
                    s.revoke(Instant.now(), RevokeReason.ADMIN_FORCED);
                    deviceSessionRepository.save(s);
                });
    }

    @Test
    @DisplayName("Two logins from distinct user-agents yield two sessions")
    void twoLoginsFromDistinctUserAgents_listsTwoSessions() throws Exception {
        login("agent/alpha");
        login("agent/beta");

        MvcResult list = mockMvc.perform(get("/api/accounts/me/sessions")
                        .header("X-Account-Id", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();

        JsonNode body = objectMapper.readTree(list.getResponse().getContentAsString());
        List<String> families = body.get("items").findValuesAsText("userAgentFamily");
        assertThat(families).hasSize(2);
    }

    @Test
    @DisplayName("Exceeding max-active-sessions evicts oldest and publishes revoke event")
    void eleventhLogin_evictsOldest_andPublishesOutboxEvent() throws Exception {
        for (int i = 1; i <= 11; i++) {
            login("agent/device-" + i);
        }

        List<DeviceSession> active = deviceSessionRepository.findActiveByAccountId(ACCOUNT_ID);
        assertThat(active).hasSize(10);

        // The outbox must contain at least one auth.session.revoked row for this account,
        // proving the eviction fired and the cascade wrote an event (scenario payload
        // detail is covered by unit tests; integration-level we only assert the row exists).
        long revokedEvents = outboxJpaRepository.findAll().stream()
                .filter(o -> "auth.session.revoked".equals(o.getEventType()))
                .filter(o -> ACCOUNT_ID.equals(o.getAggregateId()))
                .count();
        assertThat(revokedEvents).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Revoking a single session invalidates the matching refresh token")
    void deleteSingleSession_rejectsSubsequentRefresh() throws Exception {
        String refreshTokenA = loginAndExtractRefreshToken("agent/delete-me");
        String refreshTokenB = loginAndExtractRefreshToken("agent/keep");

        List<DeviceSession> sessions = deviceSessionRepository.findActiveByAccountId(ACCOUNT_ID);
        assertThat(sessions).hasSize(2);
        String deviceIdToDrop = sessions.stream()
                .filter(s -> s.getUserAgent() != null && s.getUserAgent().contains("delete-me"))
                .findFirst().orElseThrow()
                .getDeviceId();

        mockMvc.perform(delete("/api/accounts/me/sessions/" + deviceIdToDrop)
                        .header("X-Account-Id", ACCOUNT_ID))
                .andExpect(status().isNoContent());

        // Refresh with the dropped token -> 401
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refreshTokenA)))
                .andExpect(status().isUnauthorized());

        // Other token still works
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refreshTokenB)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bulk revoke keeps only the caller's current session")
    void bulkRevoke_keepsCurrentSessionOnly() throws Exception {
        login("agent/one");
        login("agent/two");
        login("agent/current");

        String currentDeviceId = deviceSessionRepository.findActiveByAccountId(ACCOUNT_ID)
                .stream()
                .filter(s -> s.getUserAgent() != null && s.getUserAgent().contains("current"))
                .findFirst().orElseThrow()
                .getDeviceId();

        MvcResult result = mockMvc.perform(delete("/api/accounts/me/sessions")
                        .header("X-Account-Id", ACCOUNT_ID)
                        .header("X-Device-Id", currentDeviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedCount").value(2))
                .andReturn();

        List<DeviceSession> remaining = deviceSessionRepository.findActiveByAccountId(ACCOUNT_ID);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getDeviceId()).isEqualTo(currentDeviceId);

        assertThat(result.getResponse().getContentAsString()).contains("\"revokedCount\":2");
    }

    // ----- helpers -----

    private MvcResult login(String userAgent) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .header("User-Agent", userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String loginAndExtractRefreshToken(String userAgent) throws Exception {
        MvcResult res = login(userAgent);
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        return body.get("refreshToken").asText();
    }
}
