package com.example.auth.integration;

import com.example.auth.infrastructure.persistence.CredentialJpaEntity;
import com.example.auth.infrastructure.persistence.CredentialJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gap.security.password.Argon2idPasswordHasher;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("auth_db")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static WireMockServer wireMock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CredentialJpaRepository credentialJpaRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String TEST_EMAIL = "user@example.com";
    private static final String TEST_PASSWORD = "password123";
    private static final String ACCOUNT_ID = "acc-integration-test";

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
        registry.add("auth.account-service.base-url", () -> "http://localhost:18082");
    }

    @BeforeEach
    void setupWireMock() {
        wireMock.resetAll();

        // Hash the test password
        Argon2idPasswordHasher hasher = new Argon2idPasswordHasher();
        String hash = hasher.hash(TEST_PASSWORD);

        // Stub account-service credential lookup
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/internal/accounts/credentials"))
                .withQueryParam("email", equalTo(TEST_EMAIL))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "accountId": "%s",
                                    "credentialHash": "%s",
                                    "hashAlgorithm": "argon2id",
                                    "accountStatus": "ACTIVE"
                                }
                                """.formatted(ACCOUNT_ID, hash))));

        // Stub for unknown email
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/internal/accounts/credentials"))
                .withQueryParam("email", equalTo("unknown@example.com"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "code": "ACCOUNT_NOT_FOUND",
                                    "message": "No account found",
                                    "timestamp": "%s"
                                }
                                """.formatted(Instant.now().toString()))));
    }

    @Test
    @Order(1)
    @DisplayName("Login succeeds and returns token pair")
    void loginSuccess() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("accessToken");
    }

    @Test
    @Order(2)
    @DisplayName("Login fails with wrong password")
    void loginFailsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrongpassword1"}
                                """.formatted(TEST_EMAIL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CREDENTIALS_INVALID"));
    }

    @Test
    @Order(3)
    @DisplayName("Login fails with unknown email")
    void loginFailsUnknownEmail() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"unknown@example.com","password":"password123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CREDENTIALS_INVALID"));
    }

    @Test
    @Order(4)
    @DisplayName("Login rate limit after 5 failures")
    void loginRateLimit() throws Exception {
        // Clear any existing failure counts
        String emailHash = "login:fail:" + hashEmail(TEST_EMAIL);
        redisTemplate.delete(emailHash);

        // Simulate 5 failures by setting the counter directly
        String key = "login:fail:" + hashEmail(TEST_EMAIL);
        redisTemplate.opsForValue().set(key, "5");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123"}
                                """.formatted(TEST_EMAIL)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));

        // Clean up
        redisTemplate.delete(key);
    }

    @Test
    @Order(5)
    @DisplayName("Login and then refresh token")
    void loginAndRefresh() throws Exception {
        // First login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(responseBody).get("refreshToken").asText();

        // Then refresh
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    @Order(6)
    @DisplayName("Login and then logout")
    void loginAndLogout() throws Exception {
        // First login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(responseBody).get("refreshToken").asText();

        // Then logout
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isNoContent());

        // Try to refresh with the same token - should fail
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(7)
    @DisplayName("JWKS endpoint returns valid JWKS")
    void jwksEndpoint() throws Exception {
        mockMvc.perform(get("/internal/auth/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"));
    }

    @Test
    @Order(8)
    @DisplayName("Account service down returns 503")
    void accountServiceDown() throws Exception {
        wireMock.resetAll();
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/internal/accounts/credentials"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withFixedDelay(6000))); // timeout

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @Order(9)
    @DisplayName("Locked account returns 403")
    void loginLockedAccount() throws Exception {
        wireMock.resetAll();
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/internal/accounts/credentials"))
                .withQueryParam("email", equalTo("locked@example.com"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "accountId": "acc-locked",
                                    "credentialHash": "dummy-hash",
                                    "hashAlgorithm": "argon2id",
                                    "accountStatus": "LOCKED"
                                }
                                """)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"locked@example.com","password":"password123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    private static String hashEmail(String email) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(email.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 10);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
