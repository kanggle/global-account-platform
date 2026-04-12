package com.example.security.application;

import com.example.security.application.event.SecurityEventPublisher;
import com.example.security.domain.detection.*;
import com.example.security.domain.repository.SuspiciousEventRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DetectSuspiciousActivityUseCaseTest {

    @Mock SuspiciousEventRepository repository;
    @Mock SecurityEventPublisher publisher;
    @Mock AccountLockClient lockClient;
    @Mock SuspiciousActivityRule alertRule;
    @Mock SuspiciousActivityRule autoLockRule;
    @Mock SuspiciousActivityRule quietRule;

    private EvaluationContext ctx() {
        return new EvaluationContext("evt-1", "auth.login.succeeded", "acc-1",
                "1.2.3.***", "fp-1", "US", Instant.now(), null);
    }

    @Test
    @DisplayName("No rule fires → returns null, no persistence, no events")
    void noFire() {
        when(quietRule.evaluate(any())).thenReturn(DetectionResult.NONE);
        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(quietRule), repository, publisher, lockClient);

        SuspiciousEvent result = useCase.detect(ctx());
        assertThat(result).isNull();
        verifyNoInteractions(repository, publisher, lockClient);
    }

    @Test
    @DisplayName("ALERT level: persist + publish suspicious.detected, no auto-lock")
    void alertLevel() {
        when(alertRule.evaluate(any()))
                .thenReturn(new DetectionResult("DEVICE_CHANGE", 50, Map.of("k", "v")));
        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(alertRule), repository, publisher, lockClient);

        SuspiciousEvent result = useCase.detect(ctx());
        assertThat(result).isNotNull();
        assertThat(result.getActionTaken()).isEqualTo(RiskLevel.ALERT);
        assertThat(result.getRiskScore()).isEqualTo(50);
        assertThat(result.getRuleCode()).isEqualTo("DEVICE_CHANGE");
        verify(repository, times(1)).save(any());
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

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(autoLockRule), repository, publisher, lockClient);

        SuspiciousEvent result = useCase.detect(ctx());
        assertThat(result).isNotNull();
        assertThat(result.getActionTaken()).isEqualTo(RiskLevel.AUTO_LOCK);
        verify(lockClient).lock(any());
        verify(publisher).publishSuspiciousDetected(any());
        verify(publisher).publishAutoLockTriggered(any(), eq(AccountLockClient.Status.SUCCESS));
        verify(publisher, never()).publishAutoLockPending(any());
        verify(repository, times(2)).save(any()); // initial + lockRequestResult update
    }

    @Test
    @DisplayName("AUTO_LOCK: FAILURE after retries → pending event emitted for operator")
    void autoLockFailureEmitsPending() {
        when(autoLockRule.evaluate(any()))
                .thenReturn(new DetectionResult("TOKEN_REUSE", 100, Map.of()));
        when(lockClient.lock(any()))
                .thenReturn(new AccountLockClient.LockResult(AccountLockClient.Status.FAILURE, 0, "timeout"));

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(autoLockRule), repository, publisher, lockClient);

        useCase.detect(ctx());

        verify(publisher).publishSuspiciousDetected(any());
        verify(publisher).publishAutoLockTriggered(any(), eq(AccountLockClient.Status.FAILURE));
        verify(publisher).publishAutoLockPending(any());
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

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(alertRule, autoLockRule), repository, publisher, lockClient);

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

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(quietRule, alertRule), repository, publisher, lockClient);

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

        DetectSuspiciousActivityUseCase useCase = new DetectSuspiciousActivityUseCase(
                List.of(autoLockRule), repository, publisher, lockClient);

        useCase.detect(ctx());

        ArgumentCaptor<SuspiciousEvent> captor = ArgumentCaptor.forClass(SuspiciousEvent.class);
        verify(repository, times(2)).save(captor.capture());
        SuspiciousEvent last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getLockRequestResult()).isEqualTo("ALREADY_LOCKED");
    }
}
