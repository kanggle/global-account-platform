package com.example.account.infrastructure.scheduler;

import com.example.account.application.event.AccountEventPublisher;
import com.example.account.domain.account.Account;
import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.StatusChangeReason;
import com.example.account.infrastructure.anonymizer.PiiAnonymizer;
import com.example.account.infrastructure.persistence.AccountJpaEntity;
import com.example.account.infrastructure.persistence.AccountJpaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Daily PII anonymization batch (retention.md §2).
 *
 * <p>Selects DELETED accounts whose grace period has expired (now - 30d) and whose
 * profile is not yet masked, then runs {@link PiiAnonymizer#anonymize(Account)}
 * for each, stamps {@code masked_at} on the profile (handled by the anonymizer),
 * and re-publishes {@code account.deleted} with {@code anonymized=true}
 * (account-events.md §account.deleted).
 *
 * <p>Trigger: {@code @Scheduled(cron = "0 0 3 * * *", zone = "UTC")} — daily at 03:00 UTC.
 *
 * <p>Failure handling (retention.md §2.9): per-account try/catch with WARN log;
 * one account's failure must not block the rest of the batch. Each successful
 * account is committed in its own transaction (per-account boundary,
 * {@link Propagation#REQUIRES_NEW}).
 */
@Slf4j
@Component
@Profile("!standalone")
@ConditionalOnProperty(name = "scheduler.anonymize.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AccountAnonymizationScheduler {

    static final Duration GRACE_PERIOD = Duration.ofDays(30);

    private static final String METRIC_PROCESSED = "scheduler.anonymize.processed";
    private static final String METRIC_FAILED = "scheduler.anonymize.failed";

    private final AccountJpaRepository accountJpaRepository;
    private final AnonymizationTransaction anonymizationTransaction;
    private final MeterRegistry meterRegistry;

    /**
     * Run the daily anonymization batch. Visible for tests.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void runAnonymizationBatch() {
        Instant threshold = Instant.now().minus(GRACE_PERIOD);
        List<AccountJpaEntity> candidates = accountJpaRepository.findAnonymizationCandidates(threshold);

        if (candidates.isEmpty()) {
            log.debug("Anonymization batch: no candidates older than {}", threshold);
            return;
        }

        log.info("Anonymization batch starting: candidates={}, threshold={}",
                candidates.size(), threshold);

        int processed = 0;
        int failed = 0;
        for (AccountJpaEntity entity : candidates) {
            String accountId = entity.getId();
            try {
                anonymizationTransaction.anonymizeOne(accountId);
                meterRegistry.counter(METRIC_PROCESSED).increment();
                processed++;
            } catch (Exception e) {
                meterRegistry.counter(METRIC_FAILED).increment();
                failed++;
                log.warn("Anonymization failed for accountId={}; skipping. cause={}",
                        accountId, e.toString());
            }
        }

        log.info("Anonymization batch complete: processed={}, failed={}", processed, failed);
    }

    /**
     * Per-account transactional unit. Lives in its own bean so that
     * {@link Transactional} works via Spring AOP (self-invocation would bypass it).
     */
    @Component
    @RequiredArgsConstructor
    static class AnonymizationTransaction {

        private final AccountJpaRepository accountJpaRepository;
        private final PiiAnonymizer piiAnonymizer;
        private final AccountEventPublisher eventPublisher;

        /**
         * Re-load the account inside this transaction (so optimistic locking + status
         * re-check can catch concurrent grace-period recovery) and run anonymization.
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void anonymizeOne(String accountId) {
            AccountJpaEntity managed = accountJpaRepository.findById(accountId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Account disappeared mid-batch: " + accountId));

            Account account = managed.toDomain();
            if (account.getStatus() != AccountStatus.DELETED) {
                throw new IllegalStateException(
                        "Account no longer DELETED (concurrent recovery?): " + accountId);
            }

            piiAnonymizer.anonymize(account);

            // Re-publish account.deleted with anonymized=true (retention.md §2.7,
            // account-events.md §account.deleted).
            eventPublisher.publishAccountDeletedAnonymized(
                    account.getId(),
                    StatusChangeReason.USER_REQUEST.name(),
                    "system",
                    null,
                    Instant.now());
        }
    }
}
