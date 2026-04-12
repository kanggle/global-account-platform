package com.example.gateway.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("isDockerAvailable")
@DisplayName("Gateway 통합 테스트")
class GatewayIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static WireMockServer authServiceMock;
    static WireMockServer accountServiceMock;
    static KeyPair keyPair;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeAll
    static void beforeAll() throws Exception {
        // Generate RSA key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        keyPair = keyGen.generateKeyPair();

        // Start WireMock for auth-service
        authServiceMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        authServiceMock.start();

        // Start WireMock for account-service
        accountServiceMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        accountServiceMock.start();

        // Setup JWKS endpoint
        RSAPublicKey rsaKey = (RSAPublicKey) keyPair.getPublic();
        String n = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toUnsignedByteArray(rsaKey.getModulus()));
        String e = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toUnsignedByteArray(rsaKey.getPublicExponent()));

        String jwksResponse = String.format("""
                {
                  "keys": [
                    {
                      "kty": "RSA",
                      "kid": "test-kid-1",
                      "use": "sig",
                      "alg": "RS256",
                      "n": "%s",
                      "e": "%s"
                    }
                  ]
                }
                """, n, e);

        authServiceMock.stubFor(get(urlEqualTo("/internal/auth/jwks"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(jwksResponse)));

        // Setup downstream auth-service login endpoint
        authServiceMock.stubFor(post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"mock-token\",\"refreshToken\":\"mock-refresh\"}")));

        // Setup downstream account-service me endpoint
        accountServiceMock.stubFor(get(urlEqualTo("/api/accounts/me"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"account-123\",\"email\":\"test@example.com\"}")));

        // Setup downstream account-service signup endpoint
        accountServiceMock.stubFor(post(urlEqualTo("/api/accounts/signup"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"new-account\"}")));
    }

    @AfterAll
    static void afterAll() {
        if (authServiceMock != null) authServiceMock.stop();
        if (accountServiceMock != null) accountServiceMock.stop();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("gateway.jwt.jwks-url",
                () -> authServiceMock.baseUrl() + "/internal/auth/jwks");
        registry.add("spring.cloud.gateway.routes[0].uri", authServiceMock::baseUrl);
        registry.add("spring.cloud.gateway.routes[0].id", () -> "auth-service");
        registry.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/api/auth/**");
        registry.add("spring.cloud.gateway.routes[1].uri", accountServiceMock::baseUrl);
        registry.add("spring.cloud.gateway.routes[1].id", () -> "account-service");
        registry.add("spring.cloud.gateway.routes[1].predicates[0]", () -> "Path=/api/accounts/**");
    }

    @BeforeEach
    void setUp() {
        // Clean up rate limit keys
        redisTemplate.keys("ratelimit:*")
                .flatMap(redisTemplate::delete)
                .collectList()
                .block();
    }

    @Test
    @DisplayName("공개 경로 /api/auth/login 인증 없이 다운스트림 전달")
    void publicRoute_login_forwardsToDownstream() {
        webTestClient.post().uri("/api/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{\"email\":\"test@example.com\",\"password\":\"password\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("mock-token");
    }

    @Test
    @DisplayName("공개 경로 /api/accounts/signup 인증 없이 다운스트림 전달")
    void publicRoute_signup_forwardsToDownstream() {
        webTestClient.post().uri("/api/accounts/signup")
                .header("Content-Type", "application/json")
                .bodyValue("{\"email\":\"new@example.com\",\"password\":\"password1!\"}")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("인증 필요 경로 + 유효 JWT -> 200 + X-Account-ID 주입")
    void protectedRoute_validJwt_forwardsWithAccountId() {
        String token = createValidToken("account-123");

        webTestClient.get().uri("/api/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        // Verify auth-service received request with X-Account-ID
        accountServiceMock.verify(getRequestedFor(urlEqualTo("/api/accounts/me"))
                .withHeader("X-Account-ID", equalTo("account-123")));
    }

    @Test
    @DisplayName("인증 필요 경로 + Authorization 헤더 없음 -> 401")
    void protectedRoute_noAuthHeader_returns401() {
        webTestClient.get().uri("/api/accounts/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOKEN_INVALID");
    }

    @Test
    @DisplayName("만료된 JWT -> 401 TOKEN_INVALID")
    void protectedRoute_expiredJwt_returns401() {
        String expiredToken = Jwts.builder()
                .header().keyId("test-kid-1").and()
                .subject("account-123")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        webTestClient.get().uri("/api/accounts/me")
                .header("Authorization", "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOKEN_INVALID");
    }

    @Test
    @DisplayName("변조된 JWT -> 401 TOKEN_INVALID")
    void protectedRoute_tamperedJwt_returns401() throws Exception {
        KeyPairGenerator otherKeyGen = KeyPairGenerator.getInstance("RSA");
        otherKeyGen.initialize(2048);
        KeyPair otherKeyPair = otherKeyGen.generateKeyPair();

        String tamperedToken = Jwts.builder()
                .header().keyId("test-kid-1").and()
                .subject("account-123")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        webTestClient.get().uri("/api/accounts/me")
                .header("Authorization", "Bearer " + tamperedToken)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOKEN_INVALID");
    }

    @Test
    @DisplayName("외부에서 X-Account-ID 직접 전송 -> gateway가 덮어씀")
    void spoofedAccountIdHeader_isOverwritten() {
        String token = createValidToken("real-account-id");

        webTestClient.get().uri("/api/accounts/me")
                .header("Authorization", "Bearer " + token)
                .header("X-Account-ID", "spoofed-id")
                .exchange()
                .expectStatus().isOk();

        accountServiceMock.verify(getRequestedFor(urlEqualTo("/api/accounts/me"))
                .withHeader("X-Account-ID", equalTo("real-account-id")));
    }

    @Test
    @DisplayName("X-Request-ID가 없으면 자동 생성되어 전달됨")
    void requestId_generated_whenMissing() {
        webTestClient.post().uri("/api/auth/login")
                .header("Content-Type", "application/json")
                .bodyValue("{\"email\":\"test@example.com\",\"password\":\"password\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-ID");
    }

    @Test
    @DisplayName("X-Request-ID가 있으면 그대로 전파됨")
    void requestId_propagated_whenPresent() {
        String customRequestId = "custom-request-id-12345";

        webTestClient.post().uri("/api/auth/login")
                .header("Content-Type", "application/json")
                .header("X-Request-ID", customRequestId)
                .bodyValue("{\"email\":\"test@example.com\",\"password\":\"password\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-ID", customRequestId);
    }

    @Test
    @DisplayName("/actuator/health 접근 가능")
    void actuatorHealth_returns200() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    private String createValidToken(String accountId) {
        return Jwts.builder()
                .header().keyId("test-kid-1").and()
                .subject(accountId)
                .claim("email", "test@example.com")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Converts BigInteger to unsigned byte array (strips leading zero byte if present).
     */
    private static byte[] toUnsignedByteArray(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) {
            byte[] result = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, result, 0, result.length);
            return result;
        }
        return bytes;
    }
}
