package com.example.security.application;

import com.example.security.application.event.SecurityEventPublisher;
import com.example.security.domain.detection.*;
import com.example.security.domain.suspicious.SuspiciousEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DetectSuspiciousActivityUseCaseTest {

    @Mock SuspiciousEventPersistenceService persistenceService;
    @Mock SecurityEventPublisher publisher;
    @Mock AccountLockClient lockClient;
    @Mock SuspiciousActivityRule alertRule;
    @Mock SuspiciousActivityRule autoLockRule;
    @Mock SuspiciousActivityRule quietRule;

    private EvaluationContext ctx() {
        return new EvaluationContext("evt-1", "auth.login.succeeded", "acc-1",
                "1.2.3.***", "fp-1", "US", Instant.now(), null);
    }

    private void stubPersistenceToEchoEvent() {
        when(persistenceService.recordSuspiciousEvent(any(), any(), any()))
                .thenAnswer(inv -> {
                    EvaluationContext c = inv.getArgument(0);
                    RiskScoreAggregator.Aggregated agg = inv.getArgument(1);
                    RiskLevel level = inv.getArgument(2);
                    DetectionResult w = agg.winner();
                    return SuspiciousEvent.create(
                            UUID.randomUUID().toString(),
                            c.accountId(),
                            w.ruleCode(),
                            w.riskScore(),
                            level,
                            w.evidence(),
                            c.eventId(),
                            Instant.now());
                });
    }

    @Test
    @DisplayName("No rule fires → returns null, no persistence, no events")
    void noFire() {
        when(quietRule.evaluate(any())).thenReturn(DetectionResult.NONE);
        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(quietRule), persistenceService, publisher, lockClient);

        SuspiciousEvent result = useCase.detect(ctx());
        assertThat(result).isNull();
        verifyNoInteractions(persistenceService, publisher, lockClient);
    }

    @Test
    @DisplayName("ALERT level: persist + publish suspicious.detected, no auto-lock")
    void alertLevel() {
        when(alertRule.evaluate(any()))
                .thenReturn(new DetectionResult("DEVICE_CHANGE", 50, Map.of("k", "v")));
        stubPersistenceToEchoEvent();
        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(alertRule), persistenceService, publisher, lockClient);

        SuspiciousEvent result = useCase.detect(ctx());
        assertThat(result).isNotNull();
        assertThat(result.getActionTaken()).isEqualTo(RiskLevel.ALERT);
        assertThat(result.getRiskScore()).isEqualTo(50);
        assertThat(result.getRuleCode()).isEqualTo("DEVICE_CHANGE");
        verify(persistenceService).recordSuspiciousEvent(any(), any(), any());
        verify(persistenceService, never()).updateLockResult(any());
        verify(publisher).publishSuspiciousDetected(any());
        verify(publisher, never()).publishAutoLockTriggered(any(), any());
        verifyNoInteractions(lockClient);
    }

    @Test
    @DisplayName("AUTO_LOCK: SUCCESS → account-service called, auto-lock event emitted")
    void autoLockSuccess() {
        when(autoLockRule.evaluate(any()))
                .thenReturn(new DetectionResult("GEO_ANOMALY", 92, Map.of("d", "x")));
        when(lockClient.lock(any()))
                .thenReturn(new AccountLockClient.LockResult(AccountLockClient.Status.SUCCESS, 200, "{}"));
        stubPersistenceToEchoEvent();

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(autoLockRule), persistenceService, publisher, lockClient);

        SuspiciousEvent result = useCase.detect(ctx());
        assertThat(result).isNotNull();
        assertThat(result.getActionTaken()).isEqualTo(RiskLevel.AUTO_LOCK);
        verify(lockClient).lock(any());
        verify(publisher).publishSuspiciousDetected(any());
        verify(publisher).publishAutoLockTriggered(any(), eq(AccountLockClient.Status.SUCCESS));
        verify(publisher, never()).publishAutoLockPending(any());
        verify(persistenceService).recordSuspiciousEvent(any(), any(), any());
        verify(persistenceService).updateLockResult(any());
    }

    @Test
    @DisplayName("AUTO_LOCK: FAILURE after retries → pending event emitted for operator")
    void autoLockFailureEmitsPending() {
        when(autoLockRule.evaluate(any()))
                .thenReturn(new DetectionResult("TOKEN_REUSE", 100, Map.of()));
        when(lockClient.lock(any()))
                .thenReturn(new AccountLockClient.LockResult(AccountLockClient.Status.FAILURE, 0, "timeout"));
        stubPersistenceToEchoEvent();

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(autoLockRule), persistenceService, publisher, lockClient);

        useCase.detect(ctx());

        verify(publisher).publishSuspiciousDetected(any());
        verify(publisher).publishAutoLockTriggered(any(), eq(AccountLockClient.Status.FAILURE));
        verify(publisher).publishAutoLockPending(any());
    }

    @Test
    @DisplayName("AUTO_LOCK: INVALID_TRANSITION normalized to FAILURE in persisted row")
    void autoLockInvalidTransitionNormalizedToFailure() {
        when(autoLockRule.evaluate(any()))
                .thenReturn(new DetectionResult("GEO_ANOMALY", 95, Map.of()));
        when(lockClient.lock(any()))
                .thenReturn(new AccountLockClient.LockResult(AccountLockClient.Status.INVALID_TRANSITION, 409, "{}"));
        stubPersistenceToEchoEvent();

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(autoLockRule), persistenceService, publisher, lockClient);

        useCase.detect(ctx());

        ArgumentCaptor<SuspiciousEvent> captor = ArgumentCaptor.forClass(SuspiciousEvent.class);
        verify(persistenceService).updateLockResult(captor.capture());
        assertThat(captor.getValue().getLockRequestResult()).isEqualTo("FAILURE");
    }

    @Test
    @DisplayName("Multiple rules fire — max score wins, winner's ruleCode is persisted")
    void maxScoreWins() {
        when(alertRule.evaluate(any()))
                .thenReturn(new DetectionResult("DEVICE_CHANGE", 50, Map.of()));
        when(autoLockRule.evaluate(any()))
                .thenReturn(new DetectionResult("GEO_ANOMALY", 92, Map.of()));
        when(lockClient.lock(any()))
                .thenReturn(new AccountLockClient.LockResult(AccountLockClient.Status.SUCCESS, 200, "{}"));
        stubPersistenceToEchoEvent();

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(alertRule, autoLockRule), persistenceService, publisher, lockClient);

        SuspiciousEvent result = useCase.detect(ctx());
        assertThat(result.getRuleCode()).isEqualTo("GEO_ANOMALY");
        assertThat(result.getRiskScore()).isEqualTo(92);
    }

    @Test
    @DisplayName("A throwing rule is treated as NONE and does not break the pipeline")
    void throwingRuleIsolated() {
        when(quietRule.evaluate(any())).thenThrow(new RuntimeException("boom"));
        when(alertRule.evaluate(any()))
                .thenReturn(new DetectionResult("DEVICE_CHANGE", 50, Map.of()));
        stubPersistenceToEchoEvent();

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(quietRule, alertRule), persistenceService, publisher, lockClient);

        SuspiciousEvent result = useCase.detect(ctx());
        assertThat(result).isNotNull();
        assertThat(result.getRuleCode()).isEqualTo("DEVICE_CHANGE");
    }

    @Test
    @DisplayName("Lock request result is persisted on the suspicious event")
    void lockResultPersisted() {
        when(autoLockRule.evaluate(any()))
                .thenReturn(new DetectionResult("VELOCITY", 96, Map.of()));
        when(lockClient.lock(any()))
                .thenReturn(new AccountLockClient.LockResult(AccountLockClient.Status.ALREADY_LOCKED, 200, "{}"));
        stubPersistenceToEchoEvent();

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(autoLockRule), persistenceService, publisher, lockClient);

        useCase.detect(ctx());

        ArgumentCaptor<SuspiciousEvent> captor = ArgumentCaptor.forClass(SuspiciousEvent.class);
        verify(persistenceService).updateLockResult(captor.capture());
        assertThat(captor.getValue().getLockRequestResult()).isEqualTo("ALREADY_LOCKED");
    }
}
