package com.example.account.infrastructure.scheduler;

import com.example.account.application.event.AccountEventPublisher;
import com.example.account.domain.account.Account;
import com.example.account.domain.profile.Profile;
import com.example.account.domain.repository.AccountRepository;
import com.example.account.domain.repository.ProfileRepository;
import com.example.account.domain.status.AccountStatus;
import com.example.account.infrastructure.anonymizer.PiiAnonymizer;
import com.example.account.infrastructure.persistence.AccountJpaEntity;
import com.example.account.infrastructure.persistence.AccountJpaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AccountAnonymizationScheduler} (TASK-BE-093).
 *
 * <p>Covers:
 * <ol>
 *   <li>Normal anonymization — eligible candidate is masked, masked_at stamped, event re-published.</li>
 *   <li>No candidates — no anonymizer calls, no events.</li>
 *   <li>Concurrent grace-period recovery (re-loaded account is no longer DELETED) — skipped, batch continues.</li>
 *   <li>Per-account failure (anonymizer throws) — skipped with WARN log; the rest of the batch proceeds.</li>
 * </ol>
 *
 * <p>The scheduler delegates the per-account transaction to its inner
 * {@code AnonymizationTransaction} bean. We exercise that bean's logic directly
 * here (it's the unit boundary that the scheduler invokes).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AccountAnonymizationScheduler 단위 테스트")
class AccountAnonymizationSchedulerTest {

    @Mock
    private AccountJpaRepository accountJpaRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private AccountEventPublisher eventPublisher;

    private MeterRegistry meterRegistry;

    private PiiAnonymizer piiAnonymizer;
    private AccountAnonymizationScheduler.AnonymizationTransaction anonymizationTransaction;
    private AccountAnonymizationScheduler scheduler;

    private static final Instant DELETED_AT_OLD = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        piiAnonymizer = new PiiAnonymizer(accountRepository, profileRepository);
        anonymizationTransaction = new AccountAnonymizationScheduler.AnonymizationTransaction(
                accountJpaRepository, piiAnonymizer, eventPublisher);
        scheduler = new AccountAnonymizationScheduler(
                accountJpaRepository, anonymizationTransaction, meterRegistry);
    }

    private AccountJpaEntity entityFromAccount(Account account) {
        return AccountJpaEntity.fromDomain(account);
    }

    private Account deletedAccount(String accountId, String email) {
        return Account.reconstitute(
                accountId, email, null,
                AccountStatus.DELETED,
                Instant.parse("2025-12-01T00:00:00Z"),
                Instant.parse("2025-12-01T00:00:00Z"),
                DELETED_AT_OLD,
                0);
    }

    private Account activeAccount(String accountId, String email) {
        return Account.reconstitute(
                accountId, email, null,
                AccountStatus.ACTIVE,
                Instant.parse("2025-12-01T00:00:00Z"),
                Instant.parse("2025-12-01T00:00:00Z"),
                null,
                0);
    }

    private Profile profileWithPii(String accountId) {
        return Profile.reconstitute(
                1L, accountId, "John Doe", "+82-10-1234-5678",
                LocalDate.of(1990, 1, 15),
                "ko-KR", "Asia/Seoul", null,
                Instant.parse("2025-12-01T00:00:00Z"),
                null);
    }

    @Test
    @DisplayName("정상 익명화 — 후보 계정의 PII를 마스킹하고 masked_at 설정 + account.deleted(anonymized=true) 발행")
    void runAnonymizationBatch_normalCandidate_anonymizesAndPublishesEvent() {
        String accountId = "acc-1";
        String originalEmail = "old@example.com";
        AccountJpaEntity candidateSnapshot = entityFromAccount(deletedAccount(accountId, originalEmail));
        AccountJpaEntity managedSnapshot = entityFromAccount(deletedAccount(accountId, originalEmail));
        Profile profile = profileWithPii(accountId);

        given(accountJpaRepository.findAnonymizationCandidates(any(Instant.class)))
                .willReturn(List.of(candidateSnapshot));
        given(accountJpaRepository.findById(accountId))
                .willReturn(Optional.of(managedSnapshot));
        given(profileRepository.findByAccountId(accountId))
                .willReturn(Optional.of(profile));

        scheduler.runAnonymizationBatch();

        // Capture the Account passed to accountRepository.save and assert on its mutation.
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getId()).isEqualTo(accountId);
        assertThat(savedAccount.getStatus()).isEqualTo(AccountStatus.DELETED);
        // email_hash is the SHA-256 full hex of the original email
        assertThat(savedAccount.getEmailHash()).hasSize(64).matches("[0-9a-f]{64}");
        // email is rewritten to anon_<hash[:12]>@deleted.local (retention.md §2.5)
        assertThat(savedAccount.getEmail()).startsWith("anon_");
        assertThat(savedAccount.getEmail()).endsWith("@deleted.local");
        assertThat(savedAccount.getEmail()).hasSize("anon_".length() + 12 + "@deleted.local".length());

        // Profile masked + masked_at stamped (Profile.maskPii() effects)
        assertThat(profile.getDisplayName()).isNull();
        assertThat(profile.getPhoneNumber()).isNull();
        assertThat(profile.getBirthDate()).isNull();
        assertThat(profile.getMaskedAt()).isNotNull();
        verify(profileRepository).save(profile);

        // Event re-published with anonymized=true (account-events.md)
        verify(eventPublisher).publishAccountDeletedAnonymized(
                eq(accountId), any(), eq("system"), eq(null), any(Instant.class));

        // Metrics: processed=1, failed not registered (counters lazily registered on first increment)
        assertThat(meterRegistry.counter("scheduler.anonymize.processed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("scheduler.anonymize.failed").counter()).isNull();
    }

    @Test
    @DisplayName("후보 없음 — 쿼리가 빈 결과를 반환하면 anonymizer/이벤트 미호출, 메트릭 카운터 미등록")
    void runAnonymizationBatch_noCandidates_doesNothing() {
        given(accountJpaRepository.findAnonymizationCandidates(any(Instant.class)))
                .willReturn(List.of());

        scheduler.runAnonymizationBatch();

        verify(accountRepository, never()).save(any(Account.class));
        verify(profileRepository, never()).save(any(Profile.class));
        verify(eventPublisher, never()).publishAccountDeletedAnonymized(
                any(), any(), any(), any(), any(Instant.class));

        // No counter registered because no increments occurred
        assertThat(meterRegistry.find("scheduler.anonymize.processed").counter()).isNull();
        assertThat(meterRegistry.find("scheduler.anonymize.failed").counter()).isNull();
    }

    @Test
    @DisplayName("동시성 보호 — 트랜잭션 내 재조회 시 status가 DELETED가 아니면 skip + 다음 후보 계속 처리")
    void runAnonymizationBatch_recoveredMidBatch_skipsAndContinues() {
        String recoveredId = "acc-recovered";
        String stillDeletedId = "acc-deleted";

        // Query layer returns the stale snapshot of the recovered account (race window).
        AccountJpaEntity recoveredStale = entityFromAccount(
                deletedAccount(recoveredId, "recovered@example.com"));
        // ...but a re-load inside the transaction sees status=ACTIVE (mid-batch recovery).
        AccountJpaEntity recoveredFresh = entityFromAccount(
                activeAccount(recoveredId, "recovered@example.com"));

        AccountJpaEntity stillDeletedStale = entityFromAccount(
                deletedAccount(stillDeletedId, "stale@example.com"));
        AccountJpaEntity stillDeletedFresh = entityFromAccount(
                deletedAccount(stillDeletedId, "stale@example.com"));
        Profile stillDeletedProfile = profileWithPii(stillDeletedId);

        given(accountJpaRepository.findAnonymizationCandidates(any(Instant.class)))
                .willReturn(List.of(recoveredStale, stillDeletedStale));
        given(accountJpaRepository.findById(recoveredId))
                .willReturn(Optional.of(recoveredFresh));
        given(accountJpaRepository.findById(stillDeletedId))
                .willReturn(Optional.of(stillDeletedFresh));
        given(profileRepository.findByAccountId(stillDeletedId))
                .willReturn(Optional.of(stillDeletedProfile));

        scheduler.runAnonymizationBatch();

        // Only the still-DELETED account is anonymized.
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(1)).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getId()).isEqualTo(stillDeletedId);

        verify(profileRepository).save(stillDeletedProfile);
        verify(eventPublisher, times(1)).publishAccountDeletedAnonymized(
                eq(stillDeletedId), any(), eq("system"), eq(null), any(Instant.class));
        // Recovered account: no event published
        verify(eventPublisher, never()).publishAccountDeletedAnonymized(
                eq(recoveredId), any(), any(), any(), any(Instant.class));

        // Metrics: 1 processed, 1 failed (skipped)
        assertThat(meterRegistry.counter("scheduler.anonymize.processed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("scheduler.anonymize.failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("개별 오류 격리 — anonymizer 호출 중 예외가 나도 다음 후보 처리는 계속된다")
    void runAnonymizationBatch_perAccountFailure_continuesWithRest() {
        String failingId = "acc-fail";
        String okId = "acc-ok";

        AccountJpaEntity failingStale = entityFromAccount(deletedAccount(failingId, "fail@example.com"));
        AccountJpaEntity failingFresh = entityFromAccount(deletedAccount(failingId, "fail@example.com"));

        AccountJpaEntity okStale = entityFromAccount(deletedAccount(okId, "ok@example.com"));
        AccountJpaEntity okFresh = entityFromAccount(deletedAccount(okId, "ok@example.com"));
        Profile okProfile = profileWithPii(okId);

        given(accountJpaRepository.findAnonymizationCandidates(any(Instant.class)))
                .willReturn(List.of(failingStale, okStale));
        given(accountJpaRepository.findById(failingId))
                .willReturn(Optional.of(failingFresh));
        given(accountJpaRepository.findById(okId))
                .willReturn(Optional.of(okFresh));
        given(profileRepository.findByAccountId(okId))
                .willReturn(Optional.of(okProfile));

        // Make the failing account throw at the accountRepository.save step.
        // The anonymizer always reaches accountRepository.save first (before profile lookup),
        // so we match by accountId via an argThat matcher.
        // Use Mockito.lenient() because under STRICT_STUBS, save(okAccount) (which doesn't
        // match this argThat) would otherwise be flagged as a potential stubbing problem.
        org.mockito.Mockito.lenient()
                .doThrow(new RuntimeException("simulated DB failure"))
                .when(accountRepository).save(argThatHasId(failingId));

        scheduler.runAnonymizationBatch();

        // Both accounts trigger save() — failingId throws, okId succeeds.
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        assertThat(accountCaptor.getAllValues())
                .extracting(Account::getId)
                .containsExactly(failingId, okId);

        // Profile save only for the okId (failing path threw before profile lookup).
        verify(profileRepository).save(okProfile);

        // Event published only for the okId.
        verify(eventPublisher).publishAccountDeletedAnonymized(
                eq(okId), any(), eq("system"), eq(null), any(Instant.class));
        verify(eventPublisher, never()).publishAccountDeletedAnonymized(
                eq(failingId), any(), any(), any(), any(Instant.class));

        // Metrics: 1 processed, 1 failed
        assertThat(meterRegistry.counter("scheduler.anonymize.processed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("scheduler.anonymize.failed").count()).isEqualTo(1.0);
    }

    /**
     * Mockito argThat matcher that selects {@link Account} instances by id.
     * Avoids reference equality, which fails under {@code STRICT_STUBS}
     * because the scheduler reconstitutes a fresh {@link Account} from the
     * JPA entity inside the transaction.
     */
    private static Account argThatHasId(String accountId) {
        return org.mockito.ArgumentMatchers.argThat(
                a -> a != null && accountId.equals(a.getId()));
    }
}
