package com.example.account.infrastructure.persistence;

import com.example.account.domain.account.Account;
import com.example.account.domain.status.AccountStatus;
import com.example.testsupport.integration.DockerAvailableCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("AccountJpaRepository 쿼리 슬라이스 테스트")
class AccountJpaRepositoryTest {

    @SuppressWarnings("resource")
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("account_db")
            .withUsername("test")
            .withPassword("test")
            .withCommand("mysqld", "--log-bin-trust-function-creators=1")
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private AccountJpaRepository accountRepo;

    @Autowired
    private ProfileJpaRepository profileRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        jdbc.update("DELETE FROM profiles");
        jdbc.update("DELETE FROM accounts");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    // ── findByEmail / existsByEmail ──────────────────────────────────────────

    @Test
    @DisplayName("findByEmail — 존재하는 이메일 → 계정 반환")
    void findByEmail_existing_returnsAccount() {
        Account account = Account.create("test@example.com");
        accountRepo.save(AccountJpaEntity.fromDomain(account));

        Optional<AccountJpaEntity> result = accountRepo.findByEmail("test@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().getEmail()).isEqualTo("test@example.com");
        assertThat(result.get().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("existsByEmail — 존재하는 이메일 → true")
    void existsByEmail_existingEmail_returnsTrue() {
        Account account = Account.create("exists@example.com");
        accountRepo.save(AccountJpaEntity.fromDomain(account));

        assertThat(accountRepo.existsByEmail("exists@example.com")).isTrue();
    }

    @Test
    @DisplayName("existsByEmail — 없는 이메일 → false")
    void existsByEmail_unknownEmail_returnsFalse() {
        assertThat(accountRepo.existsByEmail("ghost@example.com")).isFalse();
    }

    // ── findActiveDormantCandidates ─────────────────────────────────────────

    @Test
    @DisplayName("findActiveDormantCandidates — lastLoginSucceededAt < threshold → 반환")
    void findActiveDormantCandidates_pastThreshold_returnsActiveAccounts() {
        Instant threshold = Instant.now().minus(365, ChronoUnit.DAYS);
        String id = UUID.randomUUID().toString();

        // ACTIVE, lastLoginSucceededAt 2년 전
        jdbc.update(
                "INSERT INTO accounts (id, email, status, created_at, updated_at, last_login_succeeded_at, version) " +
                "VALUES (?, ?, 'ACTIVE', ?, ?, ?, 0)",
                id, id + "@ex.com",
                threshold.minus(365, ChronoUnit.DAYS).toString(),
                threshold.minus(365, ChronoUnit.DAYS).toString(),
                threshold.minus(1, ChronoUnit.DAYS).toString()
        );

        List<AccountJpaEntity> candidates = accountRepo.findActiveDormantCandidates(threshold);

        assertThat(candidates).extracting(AccountJpaEntity::getId).contains(id);
    }

    @Test
    @DisplayName("findActiveDormantCandidates — 최근 로그인 계정 제외")
    void findActiveDormantCandidates_recentLogin_excludes() {
        Instant threshold = Instant.now().minus(365, ChronoUnit.DAYS);
        String id = UUID.randomUUID().toString();

        // lastLoginSucceededAt = 1일 전 (threshold 이후)
        jdbc.update(
                "INSERT INTO accounts (id, email, status, created_at, updated_at, last_login_succeeded_at, version) " +
                "VALUES (?, ?, 'ACTIVE', ?, ?, ?, 0)",
                id, id + "@ex.com",
                Instant.now().minus(400, ChronoUnit.DAYS).toString(),
                Instant.now().toString(),
                Instant.now().minus(1, ChronoUnit.DAYS).toString()
        );

        List<AccountJpaEntity> candidates = accountRepo.findActiveDormantCandidates(threshold);

        assertThat(candidates).extracting(AccountJpaEntity::getId).doesNotContain(id);
    }

    @Test
    @DisplayName("findActiveDormantCandidates — lastLoginSucceededAt=null → createdAt 사용 (COALESCE)")
    void findActiveDormantCandidates_nullLastLogin_usesCreatedAt() {
        Instant threshold = Instant.now().minus(365, ChronoUnit.DAYS);
        String id = UUID.randomUUID().toString();

        // lastLoginSucceededAt=null, createdAt 2년 전
        jdbc.update(
                "INSERT INTO accounts (id, email, status, created_at, updated_at, version) " +
                "VALUES (?, ?, 'ACTIVE', ?, ?, 0)",
                id, id + "@ex.com",
                threshold.minus(365, ChronoUnit.DAYS).toString(),
                Instant.now().toString()
        );

        List<AccountJpaEntity> candidates = accountRepo.findActiveDormantCandidates(threshold);

        assertThat(candidates).extracting(AccountJpaEntity::getId).contains(id);
    }

    // ── findAnonymizationCandidates ─────────────────────────────────────────

    @Test
    @DisplayName("findAnonymizationCandidates — DELETED + grace 경과 + maskedAt=null → 반환")
    void findAnonymizationCandidates_deletedPastGrace_unmaskedProfile_returnsEligible() {
        Instant graceCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        String id = UUID.randomUUID().toString();

        // DELETED 40일 전
        jdbc.update(
                "INSERT INTO accounts (id, email, status, created_at, updated_at, deleted_at, version) " +
                "VALUES (?, ?, 'DELETED', ?, ?, ?, 0)",
                id, id + "@ex.com",
                Instant.now().minus(60, ChronoUnit.DAYS).toString(),
                graceCutoff.minus(1, ChronoUnit.DAYS).toString(),
                graceCutoff.minus(10, ChronoUnit.DAYS).toString()
        );
        // maskedAt=null 프로파일
        jdbc.update(
                "INSERT INTO profiles (account_id, locale, timezone, updated_at) VALUES (?, 'ko-KR', 'Asia/Seoul', ?)",
                id, Instant.now().toString()
        );

        List<AccountJpaEntity> candidates = accountRepo.findAnonymizationCandidates(graceCutoff);

        assertThat(candidates).extracting(AccountJpaEntity::getId).contains(id);
    }

    @Test
    @DisplayName("findAnonymizationCandidates — grace 기간 미경과 → 제외")
    void findAnonymizationCandidates_recentlyDeleted_excludes() {
        Instant graceCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        String id = UUID.randomUUID().toString();

        // DELETED 10일 전 (grace 기간 이내)
        jdbc.update(
                "INSERT INTO accounts (id, email, status, created_at, updated_at, deleted_at, version) " +
                "VALUES (?, ?, 'DELETED', ?, ?, ?, 0)",
                id, id + "@ex.com",
                Instant.now().minus(15, ChronoUnit.DAYS).toString(),
                Instant.now().toString(),
                Instant.now().minus(10, ChronoUnit.DAYS).toString()
        );

        List<AccountJpaEntity> candidates = accountRepo.findAnonymizationCandidates(graceCutoff);

        assertThat(candidates).extracting(AccountJpaEntity::getId).doesNotContain(id);
    }
}
