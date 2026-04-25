package com.example.account.infrastructure.scheduler;

import com.example.account.application.command.ChangeStatusCommand;
import com.example.account.application.service.AccountStatusUseCase;
import com.example.account.domain.account.Account;
import com.example.account.domain.repository.AccountRepository;
import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.StatusChangeReason;
import com.example.account.infrastructure.messaging.AccountOutboxPollingScheduler;
import com.example.account.infrastructure.persistence.AccountStatusHistoryJpaRepository;
import com.example.messaging.outbox.OutboxJpaEntity;
import com.example.messaging.outbox.OutboxJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link AccountDormantScheduler} (TASK-BE-094).
 *
 * <p>Verifies the end-to-end batch flow against a real MySQL Testcontainer:
 * <ol>
 *   <li>365일 초과 미접속 ACTIVE 계정 → 배치 실행 → DORMANT 전환, outbox에
 *       {@code account.status.changed} 이벤트 적재</li>
 *   <li>364일 미접속 ACTIVE 계정 → 배치 실행 → 전환 없음</li>
 *   <li>365일 초과 LOCKED 계정 → 배치 실행 → 전환 없음 (금지 전이 + 쿼리에서 자연 제외)</li>
 *   <li>이미 DORMANT인 계정 → 배치 실행 → 변경 없음</li>
 * </ol>
 *
 * <p>Outbox is queried directly because the spec mandates outbox as the source of truth
 * for event publication; Kafka delivery is verified separately by the outbox relay test.
 * The Kafka template and outbox poller are mocked to avoid producer metadata lookup
 * during context startup (matches AccountSignupIntegrationTest pattern).
 */
@SpringBootTest
@Testcontainers
@DisplayName("AccountDormantScheduler 통합 테스트 — 휴면 전환 배치 + outbox 발행")
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class AccountDormantSchedulerIntegrationTest {

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
    private AccountDormantScheduler scheduler;

    @Autowired
    private AccountStatusUseCase accountStatusUseCase;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountStatusHistoryJpaRepository historyRepository;

    @Autowired
    private OutboxJpaRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // The scheduler writes to outbox; we verify the outbox row directly. Avoid the
    // ~50s Kafka producer metadata lookup at context start by mocking both the
    // KafkaTemplate and the outbox relay (same rationale as AccountSignupIntegrationTest).
    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockitoBean
    private AccountOutboxPollingScheduler outboxPollingScheduler;

    @Test
    @DisplayName("365일 초과 ACTIVE 계정이 DORMANT로 전환되고 account.status.changed 이벤트가 outbox에 적재된다")
    void runDormantBatch_eligibleActive_transitionsToDormantAndEnqueuesEvent() {
        String email = "dormant-eligible-" + UUID.randomUUID() + "@example.com";
        Account account = createActiveAccount(email);
        // Push last_login_succeeded_at + created_at 366 days into the past so the COALESCE
        // dormant-candidate query (retention.md §1.3/§1.4) matches.
        Instant longAgo = Instant.now().minus(366, ChronoUnit.DAYS);
        setAccountTimestamps(account.getId(), longAgo, longAgo);

        scheduler.runDormantBatch();

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.DORMANT);

        // History append (account_status_history): exactly one row for this account.
        var history = historyRepository.findByAccountIdOrderByOccurredAtDesc(account.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getFromStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(history.get(0).getToStatus()).isEqualTo(AccountStatus.DORMANT);
        assertThat(history.get(0).getReasonCode()).isEqualTo(StatusChangeReason.DORMANT_365D);
        assertThat(history.get(0).getActorType()).isEqualTo("system");
        assertThat(history.get(0).getActorId()).isNull();

        // Outbox: account.status.changed enqueued with previousStatus=ACTIVE, currentStatus=DORMANT.
        List<OutboxJpaEntity> outboxRows = findOutboxByAggregateId(account.getId());
        assertThat(outboxRows)
                .extracting(OutboxJpaEntity::getEventType)
                .contains("account.status.changed");
        OutboxJpaEntity statusEvent = outboxRows.stream()
                .filter(e -> "account.status.changed".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(statusEvent.getAggregateType()).isEqualTo("Account");
        assertThat(statusEvent.getPayload()).contains("\"previousStatus\":\"ACTIVE\"");
        assertThat(statusEvent.getPayload()).contains("\"currentStatus\":\"DORMANT\"");
        assertThat(statusEvent.getPayload()).contains("\"reasonCode\":\"DORMANT_365D\"");
        assertThat(statusEvent.getPayload()).contains("\"actorType\":\"system\"");
    }

    @Test
    @DisplayName("364일 미접속 ACTIVE 계정은 휴면 임계 미달이므로 전환되지 않는다")
    void runDormantBatch_belowThreshold_doesNotTransition() {
        String email = "dormant-fresh-" + UUID.randomUUID() + "@example.com";
        Account account = createActiveAccount(email);
        // 364 days — under the 365-day threshold.
        Instant withinWindow = Instant.now().minus(364, ChronoUnit.DAYS);
        setAccountTimestamps(account.getId(), withinWindow, withinWindow);

        scheduler.runDormantBatch();

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        // No history row created for this account.
        assertThat(historyRepository.findByAccountIdOrderByOccurredAtDesc(account.getId()))
                .isEmpty();

        // No outbox row produced for this account by the dormant batch.
        assertThat(findOutboxByAggregateId(account.getId()))
                .extracting(OutboxJpaEntity::getEventType)
                .doesNotContain("account.status.changed");
    }

    @Test
    @DisplayName("LOCKED 상태 계정은 365일 초과여도 휴면 전환 대상에서 자연 제외된다 (전이 금지 + 쿼리 status=ACTIVE 필터)")
    void runDormantBatch_lockedAccount_isExcluded() {
        String email = "dormant-locked-" + UUID.randomUUID() + "@example.com";
        Account account = createActiveAccount(email);
        Instant longAgo = Instant.now().minus(400, ChronoUnit.DAYS);
        setAccountTimestamps(account.getId(), longAgo, longAgo);

        // Lock the account first via the application use case (legitimate ACTIVE → LOCKED transition).
        accountStatusUseCase.changeStatus(new ChangeStatusCommand(
                account.getId(), AccountStatus.LOCKED, StatusChangeReason.ADMIN_LOCK,
                "admin", "op-1", null));

        long historyCountBefore = historyRepository.findByAccountIdOrderByOccurredAtDesc(account.getId()).size();
        long statusEventsBefore = countStatusChangedEvents(account.getId());

        scheduler.runDormantBatch();

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        // Stays LOCKED — the dormant query filters status='ACTIVE' so this row is never picked up,
        // and even if it were, the state machine forbids LOCKED → DORMANT (account-lifecycle.md).
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.LOCKED);

        // No additional history rows added by the dormant batch.
        assertThat(historyRepository.findByAccountIdOrderByOccurredAtDesc(account.getId()))
                .hasSize((int) historyCountBefore);

        // No additional account.status.changed outbox rows added by the dormant batch.
        assertThat(countStatusChangedEvents(account.getId())).isEqualTo(statusEventsBefore);
    }

    @Test
    @DisplayName("이미 DORMANT인 계정은 배치에서 다시 처리되지 않는다 (status='ACTIVE' 필터에 의해 제외)")
    void runDormantBatch_alreadyDormant_isNotReprocessed() {
        String email = "dormant-already-" + UUID.randomUUID() + "@example.com";
        Account account = createActiveAccount(email);
        Instant longAgo = Instant.now().minus(400, ChronoUnit.DAYS);
        setAccountTimestamps(account.getId(), longAgo, longAgo);

        // First batch run: ACTIVE → DORMANT.
        scheduler.runDormantBatch();
        Account afterFirstRun = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(afterFirstRun.getStatus()).isEqualTo(AccountStatus.DORMANT);

        long historyCountAfterFirst = historyRepository.findByAccountIdOrderByOccurredAtDesc(account.getId()).size();
        long statusEventsAfterFirst = countStatusChangedEvents(account.getId());

        // Second batch run: should be a no-op for this account because it is no longer ACTIVE.
        scheduler.runDormantBatch();

        Account afterSecondRun = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(afterSecondRun.getStatus()).isEqualTo(AccountStatus.DORMANT);

        // History count unchanged — no duplicate transition row.
        assertThat(historyRepository.findByAccountIdOrderByOccurredAtDesc(account.getId()))
                .hasSize((int) historyCountAfterFirst);

        // No additional outbox rows.
        assertThat(countStatusChangedEvents(account.getId())).isEqualTo(statusEventsAfterFirst);
    }

    private Account createActiveAccount(String email) {
        Account account = Account.create(email);
        return accountRepository.save(account);
    }

    /**
     * Set {@code created_at} and {@code last_login_succeeded_at} to a fixed past instant so the
     * COALESCE-based dormant-candidate query selects this row. We must bypass the JPA entity
     * (which doesn't carry {@code last_login_succeeded_at} through {@code fromDomain}) and use
     * raw SQL.
     */
    private void setAccountTimestamps(String accountId, Instant createdAt, Instant lastLoginSucceededAt) {
        jdbcTemplate.update(
                "UPDATE accounts SET created_at = ?, last_login_succeeded_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                Timestamp.from(lastLoginSucceededAt),
                accountId);
    }

    private List<OutboxJpaEntity> findOutboxByAggregateId(String aggregateId) {
        return outboxRepository.findAll().stream()
                .filter(e -> aggregateId.equals(e.getAggregateId()))
                .toList();
    }

    private long countStatusChangedEvents(String aggregateId) {
        return outboxRepository.findAll().stream()
                .filter(e -> aggregateId.equals(e.getAggregateId()))
                .filter(e -> "account.status.changed".equals(e.getEventType()))
                .count();
    }
}
