package com.example.account.infrastructure.scheduler;

import com.example.account.application.command.ChangeStatusCommand;
import com.example.account.application.service.AccountStatusUseCase;
import com.example.account.domain.account.Account;
import com.example.account.domain.profile.Profile;
import com.example.account.domain.repository.AccountRepository;
import com.example.account.domain.repository.ProfileRepository;
import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.StatusChangeReason;
import com.example.account.infrastructure.messaging.AccountOutboxPollingScheduler;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link AccountAnonymizationScheduler} (TASK-BE-094).
 *
 * <p>Verifies the end-to-end anonymization batch flow against a real MySQL Testcontainer:
 * <ol>
 *   <li>30일 초과 DELETED + masked_at IS NULL → 배치 → masked_at 설정, PII 마스킹,
 *       outbox에 {@code account.deleted (anonymized=true)} 적재</li>
 *   <li>29일 DELETED + masked_at IS NULL → 배치 → 처리 없음 (유예 기간 내)</li>
 *   <li>DELETED + masked_at 이미 설정 → 배치 → 중복 처리 없음 (멱등성 가드)</li>
 *   <li>30일 초과지만 grace period 복구 (status=ACTIVE) → 배치 → 처리 없음
 *       (쿼리 status='DELETED' 필터에 의해 자연 제외)</li>
 * </ol>
 *
 * <p>Outbox is queried directly because the spec mandates outbox as the source of truth
 * for event publication. Kafka template + outbox poller are mocked to avoid producer
 * metadata lookup at context startup (matches AccountSignupIntegrationTest pattern).
 */
@SpringBootTest
@Testcontainers
@DisplayName("AccountAnonymizationScheduler 통합 테스트 — PII 익명화 배치 + outbox 발행")
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class AccountAnonymizationSchedulerIntegrationTest {

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
    private AccountAnonymizationScheduler scheduler;

    @Autowired
    private AccountStatusUseCase accountStatusUseCase;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private OutboxJpaRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockitoBean
    private AccountOutboxPollingScheduler outboxPollingScheduler;

    @Test
    @DisplayName("30일 초과 DELETED + masked_at NULL — 배치 실행 후 PII 마스킹, masked_at 설정, account.deleted(anonymized=true) 발행")
    void runAnonymizationBatch_eligibleDeletedAccount_anonymizesAndPublishesEvent() {
        String email = "anonymize-eligible-" + UUID.randomUUID() + "@example.com";
        Account account = createDeletedAccountWithProfile(email);
        // Push deleted_at 31 days into the past (beyond the 30-day grace period).
        setDeletedAt(account.getId(), Instant.now().minus(31, ChronoUnit.DAYS));

        long outboxRowsBefore = countOutboxByAggregate(account.getId());

        scheduler.runAnonymizationBatch();

        // accounts.email rewritten to anon_<sha256[:12]>@deleted.local; email_hash full hex.
        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.DELETED);
        assertThat(reloaded.getEmail()).startsWith("anon_");
        assertThat(reloaded.getEmail()).endsWith("@deleted.local");
        assertThat(reloaded.getEmail()).hasSize("anon_".length() + 12 + "@deleted.local".length());
        assertThat(reloaded.getEmailHash()).hasSize(64).matches("[0-9a-f]{64}");

        // Profile masked + masked_at stamped.
        Profile profile = profileRepository.findByAccountId(account.getId()).orElseThrow();
        assertThat(profile.getDisplayName()).isNull();
        assertThat(profile.getPhoneNumber()).isNull();
        assertThat(profile.getBirthDate()).isNull();
        assertThat(profile.getMaskedAt()).isNotNull();

        // Outbox: a new account.deleted row with anonymized=true was appended by this batch.
        long outboxRowsAfter = countOutboxByAggregate(account.getId());
        assertThat(outboxRowsAfter).isGreaterThan(outboxRowsBefore);

        OutboxJpaEntity anonymizedEvent = findOutboxByAggregate(account.getId()).stream()
                .filter(e -> "account.deleted".equals(e.getEventType()))
                .filter(e -> e.getPayload().contains("\"anonymized\":true"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected an account.deleted outbox row with anonymized=true"));
        assertThat(anonymizedEvent.getAggregateType()).isEqualTo("Account");
        assertThat(anonymizedEvent.getPayload()).contains("\"actorType\":\"system\"");
    }

    @Test
    @DisplayName("29일 DELETED + masked_at NULL — 유예 기간 내이므로 익명화되지 않는다")
    void runAnonymizationBatch_belowGracePeriod_doesNotAnonymize() {
        String email = "anonymize-fresh-" + UUID.randomUUID() + "@example.com";
        Account account = createDeletedAccountWithProfile(email);
        // 29 days — under the 30-day grace period.
        setDeletedAt(account.getId(), Instant.now().minus(29, ChronoUnit.DAYS));

        long anonymizedEventsBefore = countAnonymizedDeletedEvents(account.getId());

        scheduler.runAnonymizationBatch();

        // Email + profile remain untouched.
        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(email);
        assertThat(reloaded.getEmailHash()).isNull();

        Profile profile = profileRepository.findByAccountId(account.getId()).orElseThrow();
        assertThat(profile.getDisplayName()).isEqualTo("John Doe");
        assertThat(profile.getMaskedAt()).isNull();

        // No new account.deleted (anonymized=true) row in outbox.
        assertThat(countAnonymizedDeletedEvents(account.getId())).isEqualTo(anonymizedEventsBefore);
    }

    @Test
    @DisplayName("DELETED + masked_at 이미 설정됨 — 멱등성 가드에 의해 중복 처리되지 않는다")
    void runAnonymizationBatch_alreadyAnonymized_isNotReprocessed() {
        String email = "anonymize-done-" + UUID.randomUUID() + "@example.com";
        Account account = createDeletedAccountWithProfile(email);
        setDeletedAt(account.getId(), Instant.now().minus(60, ChronoUnit.DAYS));

        // Pre-mark the profile as already anonymized (simulates a prior run completing).
        Instant priorMaskedAt = Instant.now().minus(10, ChronoUnit.DAYS);
        jdbcTemplate.update(
                "UPDATE profiles SET masked_at = ?, display_name = NULL, phone_number = NULL, birth_date = NULL "
                        + "WHERE account_id = ?",
                Timestamp.from(priorMaskedAt),
                account.getId());
        // Also rewrite the email to a unique anonymized-form value so we can detect any second-pass
        // corruption. The 12-char prefix is hex chars from the account UUID for per-test uniqueness
        // (the email column has a UNIQUE constraint).
        String hexFromId = account.getId().replace("-", "").substring(0, 12);
        String preAnonEmail = "anon_" + hexFromId + "@deleted.local";
        String preAnonHash = "a".repeat(64);
        jdbcTemplate.update(
                "UPDATE accounts SET email = ?, email_hash = ? WHERE id = ?",
                preAnonEmail, preAnonHash, account.getId());

        long anonymizedEventsBefore = countAnonymizedDeletedEvents(account.getId());

        scheduler.runAnonymizationBatch();

        // Email and email_hash unchanged — no second masking pass corrupted the values.
        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(preAnonEmail);
        assertThat(reloaded.getEmailHash()).isEqualTo(preAnonHash);

        Profile profile = profileRepository.findByAccountId(account.getId()).orElseThrow();
        assertThat(profile.getMaskedAt()).isNotNull();
        // masked_at must NOT be advanced — same instant as the prior run (truncated to MySQL precision).
        assertThat(profile.getMaskedAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(priorMaskedAt.truncatedTo(ChronoUnit.SECONDS));

        // No additional account.deleted (anonymized=true) outbox row.
        assertThat(countAnonymizedDeletedEvents(account.getId())).isEqualTo(anonymizedEventsBefore);
    }

    @Test
    @DisplayName("30일 초과지만 grace period 복구된 ACTIVE 계정 — 쿼리 status='DELETED' 필터로 자연 제외")
    void runAnonymizationBatch_recoveredAccount_isExcluded() {
        String email = "anonymize-recovered-" + UUID.randomUUID() + "@example.com";
        Account account = createDeletedAccountWithProfile(email);
        setDeletedAt(account.getId(), Instant.now().minus(45, ChronoUnit.DAYS));

        // Recover within grace period (DELETED → ACTIVE via WITHIN_GRACE_PERIOD reason).
        accountStatusUseCase.changeStatus(new ChangeStatusCommand(
                account.getId(), AccountStatus.ACTIVE, StatusChangeReason.WITHIN_GRACE_PERIOD,
                "admin", "op-1", null));

        Account beforeBatch = accountRepository.findById(account.getId()).orElseThrow();
        // Sanity: account is back to ACTIVE and deleted_at was cleared by Account.changeStatus().
        assertThat(beforeBatch.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(beforeBatch.getDeletedAt()).isNull();

        long anonymizedEventsBefore = countAnonymizedDeletedEvents(account.getId());

        scheduler.runAnonymizationBatch();

        // PII intact: email unchanged, hash still NULL, profile fields preserved.
        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(email);
        assertThat(reloaded.getEmailHash()).isNull();

        Profile profile = profileRepository.findByAccountId(account.getId()).orElseThrow();
        assertThat(profile.getDisplayName()).isEqualTo("John Doe");
        assertThat(profile.getMaskedAt()).isNull();

        // No new anonymized event published.
        assertThat(countAnonymizedDeletedEvents(account.getId())).isEqualTo(anonymizedEventsBefore);
    }

    /**
     * Build a DELETED account with a fully populated profile. Uses the application use case
     * for the ACTIVE → DELETED transition so the row reaches DELETED through the legitimate
     * state-machine path (history + initial outbox event included).
     */
    private Account createDeletedAccountWithProfile(String email) {
        Account account = Account.create(email);
        Account saved = accountRepository.save(account);

        Profile profile = Profile.create(saved.getId(), "John Doe", "ko-KR", "Asia/Seoul");
        profile.update("John Doe", "+82-10-1234-5678", LocalDate.of(1990, 1, 15),
                "ko-KR", "Asia/Seoul", null);
        profileRepository.save(profile);

        accountStatusUseCase.deleteAccount(saved.getId(),
                StatusChangeReason.USER_REQUEST, "user", saved.getId());
        return accountRepository.findById(saved.getId()).orElseThrow();
    }

    /** Push {@code deleted_at} to a fixed past instant so the anonymization-candidate query matches. */
    private void setDeletedAt(String accountId, Instant deletedAt) {
        jdbcTemplate.update(
                "UPDATE accounts SET deleted_at = ? WHERE id = ?",
                Timestamp.from(deletedAt),
                accountId);
    }

    private List<OutboxJpaEntity> findOutboxByAggregate(String aggregateId) {
        return outboxRepository.findAll().stream()
                .filter(e -> aggregateId.equals(e.getAggregateId()))
                .toList();
    }

    private long countOutboxByAggregate(String aggregateId) {
        return findOutboxByAggregate(aggregateId).size();
    }

    private long countAnonymizedDeletedEvents(String aggregateId) {
        return findOutboxByAggregate(aggregateId).stream()
                .filter(e -> "account.deleted".equals(e.getEventType()))
                .filter(e -> e.getPayload().contains("\"anonymized\":true"))
                .count();
    }
}
