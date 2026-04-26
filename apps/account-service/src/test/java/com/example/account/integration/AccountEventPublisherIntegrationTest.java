package com.example.account.integration;

import com.example.account.application.event.AccountEventPublisher;
import com.example.account.domain.account.Account;
import com.example.account.domain.status.AccountStatus;
import com.example.messaging.outbox.OutboxPollingScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-BE-118: integration test for {@link AccountEventPublisher#publishAccountLocked}.
 *
 * <p>Wires the real {@code AccountEventPublisher}, {@code OutboxWriter}, and
 * {@code ObjectMapper} from the Spring context against a Testcontainers MySQL
 * instance, then asserts the outbox row written by {@code publishAccountLocked}
 * matches {@code specs/contracts/events/account-events.md} for {@code account.locked}:
 * <ul>
 *   <li>payload contains {@code eventId, accountId, reasonCode, actorType, lockedAt}</li>
 *   <li>{@code eventId} is a valid UUID v7 (RFC 9562 — 4-bit version field is 7)</li>
 * </ul>
 *
 * <p>Pairs with {@code AccountEventPublisherTest} (unit) which mocks the
 * outbox writer; this test exercises the full domain factory → publisher →
 * outbox table path with real serialization.
 */
@SpringBootTest
@Testcontainers
@DisplayName("TASK-BE-118: AccountEventPublisher integration — account.locked outbox payload")
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class AccountEventPublisherIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("account_db")
            .withUsername("account_user")
            .withPassword("account_pass")
            .withCommand("mysqld",
                    "--default-authentication-plugin=mysql_native_password",
                    "--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("internal.api.token", () -> "test-internal-token");
    }

    @Autowired
    private AccountEventPublisher accountEventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // The outbox poller runs on a Spring schedule and would race with the
    // assertions below by flipping `status` to PUBLISHED (and attempting to
    // talk to Kafka). Stub it out — this test only cares about what was
    // written to the outbox table by AccountEventPublisher.
    @MockitoBean
    private OutboxPollingScheduler outboxPollingScheduler;

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    private Account lockedAccount(String id) {
        return Account.reconstitute(id, "user@example.com", null, AccountStatus.LOCKED,
                Instant.now(), Instant.now(), null, null, null, 0);
    }

    @Test
    @Transactional
    @DisplayName("publishAccountLocked writes outbox row whose payload matches the contract and carries a UUID v7 eventId")
    void publishAccountLocked_writesContractCompliantPayload() throws Exception {
        String accountId = "acc-" + UUID.randomUUID();
        Instant lockedAt = Instant.parse("2026-04-26T12:00:00Z");

        accountEventPublisher.publishAccountLocked(
                lockedAccount(accountId), "ADMIN_LOCK", "operator", "op-7", lockedAt);

        // Read the row that was just written. We filter by aggregate_id +
        // event_type so co-resident rows from prior tests don't bleed in
        // (the @Transactional wrapper rolls this row back at test end).
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT aggregate_type, aggregate_id, event_type, payload, status " +
                        "FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                accountId, "account.locked");

        assertThat(row.get("aggregate_type")).isEqualTo("Account");
        assertThat(row.get("aggregate_id")).isEqualTo(accountId);
        assertThat(row.get("event_type")).isEqualTo("account.locked");
        assertThat(row.get("status")).isEqualTo("PENDING");

        JsonNode payload = objectMapper.readTree((String) row.get("payload"));

        // Contract fields per specs/contracts/events/account-events.md (account.locked).
        assertThat(payload.has("eventId")).isTrue();
        assertThat(payload.has("accountId")).isTrue();
        assertThat(payload.has("reasonCode")).isTrue();
        assertThat(payload.has("actorType")).isTrue();
        assertThat(payload.has("lockedAt")).isTrue();

        assertThat(payload.get("accountId").asText()).isEqualTo(accountId);
        assertThat(payload.get("reasonCode").asText()).isEqualTo("ADMIN_LOCK");
        assertThat(payload.get("actorType").asText()).isEqualTo("operator");
        assertThat(payload.get("actorId").asText()).isEqualTo("op-7");
        assertThat(payload.get("lockedAt").asText()).isEqualTo("2026-04-26T12:00:00Z");

        // TASK-BE-118 — the contract requires UUID v7 (RFC 9562). The 4-bit
        // version field exposed by UUID#version() must be 7.
        String eventId = payload.get("eventId").asText();
        UUID parsed = UUID.fromString(eventId);
        assertThat(parsed.version())
                .as("account.locked.eventId must be UUID v7 per the event contract")
                .isEqualTo(7);
    }

    @Test
    @Transactional
    @DisplayName("publishAccountLocked omits actorId from payload when actorId is null")
    void publishAccountLocked_nullActorId_omitsField() throws Exception {
        String accountId = "acc-" + UUID.randomUUID();
        Instant lockedAt = Instant.parse("2026-04-26T12:00:01Z");

        accountEventPublisher.publishAccountLocked(
                lockedAccount(accountId), "AUTO_DETECT", "system", null, lockedAt);

        String payloadJson = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                String.class, accountId, "account.locked");

        JsonNode payload = objectMapper.readTree(payloadJson);
        assertThat(payload.has("actorId"))
                .as("actorId must be omitted when null per the contract")
                .isFalse();
        assertThat(payload.get("actorType").asText()).isEqualTo("system");
        assertThat(payload.get("reasonCode").asText()).isEqualTo("AUTO_DETECT");

        // eventId still UUID v7.
        assertThat(UUID.fromString(payload.get("eventId").asText()).version()).isEqualTo(7);
    }
}
