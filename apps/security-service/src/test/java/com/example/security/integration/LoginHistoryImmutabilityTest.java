package com.example.security.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying that login_history table is append-only.
 * UPDATE and DELETE are rejected by MySQL triggers.
 */
@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class LoginHistoryImmutabilityTest {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("security_db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("UPDATE on login_history is rejected by trigger")
    void updateIsRejected() {
        // Insert a test row
        jdbcTemplate.update(
                "INSERT INTO login_history (event_id, account_id, outcome, occurred_at) VALUES (?, ?, ?, ?)",
                "evt-immutability-update-" + Instant.now().toEpochMilli(),
                "acc-immutability-001",
                "SUCCESS",
                Instant.now()
        );

        // Attempt UPDATE should be rejected by trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE login_history SET outcome = 'FAILURE' WHERE account_id = ?",
                        "acc-immutability-001"
                )
        ).hasMessageContaining("UPDATE not allowed on login_history");
    }

    @Test
    @DisplayName("DELETE on login_history is rejected by trigger")
    void deleteIsRejected() {
        // Insert a test row
        jdbcTemplate.update(
                "INSERT INTO login_history (event_id, account_id, outcome, occurred_at) VALUES (?, ?, ?, ?)",
                "evt-immutability-delete-" + Instant.now().toEpochMilli(),
                "acc-immutability-002",
                "SUCCESS",
                Instant.now()
        );

        // Attempt DELETE should be rejected by trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "DELETE FROM login_history WHERE account_id = ?",
                        "acc-immutability-002"
                )
        ).hasMessageContaining("DELETE not allowed on login_history");
    }
}
