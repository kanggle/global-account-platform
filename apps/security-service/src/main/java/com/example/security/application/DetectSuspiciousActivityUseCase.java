package com.example.security.application;

import com.example.security.application.event.SecurityEventPublisher;
import com.example.security.domain.detection.AccountLockClient;
import com.example.security.domain.detection.DetectionResult;
import com.example.security.domain.detection.EvaluationContext;
import com.example.security.domain.detection.RiskLevel;
import com.example.security.domain.detection.RiskScoreAggregator;
import com.example.security.domain.detection.SuspiciousActivityRule;
import com.example.security.domain.repository.SuspiciousEventRepository;
import com.example.security.domain.suspicious.SuspiciousEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the detection pipeline for a single consumed auth event:
 * <ol>
 *   <li>Evaluate every registered {@link SuspiciousActivityRule} in order.</li>
 *   <li>Aggregate results (max score wins).</li>
 *   <li>Decide {@link RiskLevel} action:
 *     <ul>
 *       <li>NONE → no-op</li>
 *       <li>ALERT → persist suspicious_events + publish {@code suspicious.detected}</li>
 *       <li>AUTO_LOCK → persist + publish + call account-service lock (outside tx)</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>The account-service call and the {@code auto.lock.triggered} event are
 * performed <em>after</em> the DB transaction commits so that a network failure
 * in the HTTP call does not block or roll back the suspicious record. The record
 * is updated with the final {@code lockRequestResult} in a second short
 * transaction. This mirrors the outbox pattern for side effects.</p>
 */
@Slf4j
@Service
public class DetectSuspiciousActivityUseCase {

    private final List<SuspiciousActivityRule> rules;
    private final SuspiciousEventRepository suspiciousEventRepository;
    private final SecurityEventPublisher publisher;
    private final AccountLockClient accountLockClient;

    public DetectSuspiciousActivityUseCase(List<SuspiciousActivityRule> rules,
                                            SuspiciousEventRepository suspiciousEventRepository,
                                            SecurityEventPublisher publisher,
                                            AccountLockClient accountLockClient) {
        this.rules = List.copyOf(rules);
        this.suspiciousEventRepository = suspiciousEventRepository;
        this.publisher = publisher;
        this.accountLockClient = accountLockClient;
    }

    /**
     * Entry point — runs the pipeline and returns the persisted SuspiciousEvent,
     * or null if no rule fired above the NONE threshold.
     */
    public SuspiciousEvent detect(EvaluationContext ctx) {
        if (ctx == null || !ctx.hasAccount()) {
            return null;
        }
        List<DetectionResult> results = new ArrayList<>(rules.size());
        for (SuspiciousActivityRule rule : rules) {
            try {
                DetectionResult r = rule.evaluate(ctx);
                results.add(r == null ? DetectionResult.NONE : r);
            } catch (RuntimeException e) {
                log.warn("Rule {} threw; treating as NONE for eventId={}", rule.ruleCode(), ctx.eventId(), e);
                results.add(DetectionResult.NONE);
            }
        }
        RiskScoreAggregator.Aggregated aggregated = RiskScoreAggregator.aggregate(results);
        if (!aggregated.anyFired()) {
            return null;
        }
        RiskLevel level = aggregated.level();
        if (level == RiskLevel.NONE) {
            return null;
        }

        SuspiciousEvent persisted = recordSuspiciousEvent(ctx, aggregated, level);
        publisher.publishSuspiciousDetected(persisted);

        if (level == RiskLevel.AUTO_LOCK) {
            triggerAutoLock(persisted);
        }
        return persisted;
    }

    @Transactional
    protected SuspiciousEvent recordSuspiciousEvent(EvaluationContext ctx,
                                                     RiskScoreAggregator.Aggregated aggregated,
                                                     RiskLevel level) {
        DetectionResult winner = aggregated.winner();
        SuspiciousEvent event = SuspiciousEvent.create(
                UUID.randomUUID().toString(),
                ctx.accountId(),
                winner.ruleCode(),
                winner.riskScore(),
                level,
                winner.evidence(),
                ctx.eventId(),
                Instant.now()
        );
        suspiciousEventRepository.save(event);
        log.info("Persisted suspicious event: id={}, accountId={}, ruleCode={}, score={}, action={}",
                event.getId(), event.getAccountId(), event.getRuleCode(), event.getRiskScore(), level);
        return event;
    }

    private void triggerAutoLock(SuspiciousEvent event) {
        AccountLockClient.LockResult result = accountLockClient.lock(event);
        String code = switch (result.status()) {
            case SUCCESS -> "SUCCESS";
            case ALREADY_LOCKED -> "ALREADY_LOCKED";
            case INVALID_TRANSITION -> "INVALID_TRANSITION";
            case FAILURE -> "FAILURE";
        };
        SuspiciousEvent updated = event.withLockRequestResult(code);
        updateLockResult(updated);
        publisher.publishAutoLockTriggered(updated, result.status());

        if (result.status() == AccountLockClient.Status.FAILURE) {
            publisher.publishAutoLockPending(updated);
            log.warn("Auto-lock FAILURE — emitted pending event; suspiciousEventId={}, accountId={}",
                    updated.getId(), updated.getAccountId());
        }
    }

    @Transactional
    protected void updateLockResult(SuspiciousEvent event) {
        suspiciousEventRepository.save(event);
    }
}
