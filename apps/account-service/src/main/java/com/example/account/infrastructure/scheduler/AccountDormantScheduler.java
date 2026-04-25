package com.example.account.infrastructure.scheduler;

import com.example.account.application.command.ChangeStatusCommand;
import com.example.account.application.service.AccountStatusUseCase;
import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.StatusChangeReason;
import com.example.account.infrastructure.persistence.AccountJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDormantScheduler {

    private final AccountJpaRepository accountJpaRepository;
    private final AccountStatusUseCase accountStatusUseCase;

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void activateDormantAccounts() {
        Instant threshold = Instant.now().minus(365, ChronoUnit.DAYS);
        var candidates = accountJpaRepository.findActiveDormantCandidates(threshold);

        if (candidates.isEmpty()) {
            log.info("[DormantScheduler] no candidates found (threshold={})", threshold);
            return;
        }

        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (var entity : candidates) {
            try {
                accountStatusUseCase.changeStatus(new ChangeStatusCommand(
                        entity.getId(),
                        AccountStatus.DORMANT,
                        StatusChangeReason.DORMANT_365D,
                        "system",
                        null,
                        null
                ));
                processed.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
                log.warn("[DormantScheduler] failed to transition account {} to DORMANT: {}",
                        entity.getId(), e.getMessage());
            }
        }

        log.info("[DormantScheduler] done — processed={}, failed={}", processed.get(), failed.get());
    }
}
