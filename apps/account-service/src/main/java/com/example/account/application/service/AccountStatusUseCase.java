package com.example.account.application.service;

import com.example.account.application.command.ChangeStatusCommand;
import com.example.account.application.event.AccountEventPublisher;
import com.example.account.application.exception.AccountNotFoundException;
import com.example.account.application.result.AccountStatusResult;
import com.example.account.application.result.DeleteAccountResult;
import com.example.account.application.result.StatusChangeResult;
import com.example.account.domain.account.Account;
import com.example.account.domain.history.AccountStatusHistoryEntry;
import com.example.account.domain.repository.AccountRepository;
import com.example.account.domain.repository.AccountStatusHistoryRepository;
import com.example.account.domain.status.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class AccountStatusUseCase {

    private final AccountRepository accountRepository;
    private final AccountStatusHistoryRepository historyRepository;
    private final AccountStatusMachine statusMachine;
    private final AccountEventPublisher eventPublisher;
    private final int gracePeriodDays;

    public AccountStatusUseCase(AccountRepository accountRepository,
                                 AccountStatusHistoryRepository historyRepository,
                                 AccountStatusMachine statusMachine,
                                 AccountEventPublisher eventPublisher,
                                 @Value("${account.deletion.grace-period-days:30}") int gracePeriodDays) {
        this.accountRepository = accountRepository;
        this.historyRepository = historyRepository;
        this.statusMachine = statusMachine;
        this.eventPublisher = eventPublisher;
        this.gracePeriodDays = gracePeriodDays;
    }

    @Transactional(readOnly = true)
    public AccountStatusResult getStatus(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        var latestHistory = historyRepository.findTopByAccountIdOrderByOccurredAtDesc(accountId);

        return new AccountStatusResult(
                account.getId(),
                account.getStatus().name(),
                latestHistory.map(AccountStatusHistoryEntry::getOccurredAt).orElse(account.getCreatedAt()),
                latestHistory.map(h -> h.getReasonCode().name()).orElse(null)
        );
    }

    @Transactional
    public StatusChangeResult changeStatus(ChangeStatusCommand command) {
        Account account = accountRepository.findById(command.accountId())
                .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

        AccountStatus previousStatus = account.getStatus();
        StatusTransition transition = account.changeStatus(
                statusMachine, command.targetStatus(), command.reason());

        accountRepository.save(account);

        recordStatusHistory(account.getId(), transition, command);

        Instant now = Instant.now();
        publishStatusChangeEvents(account, previousStatus, command, now);

        return new StatusChangeResult(
                account.getId(),
                previousStatus.name(),
                account.getStatus().name(),
                now
        );
    }

    private void recordStatusHistory(String accountId, StatusTransition transition,
                                      ChangeStatusCommand command) {
        AccountStatusHistoryEntry historyEntry = AccountStatusHistoryEntry.create(
                accountId,
                transition.from(),
                transition.to(),
                transition.reason(),
                command.actorType(),
                command.actorId(),
                command.details()
        );
        historyRepository.save(historyEntry);
    }

    private void publishStatusChangeEvents(Account account, AccountStatus previousStatus,
                                            ChangeStatusCommand command, Instant now) {
        if (previousStatus == command.targetStatus()) {
            return;
        }

        eventPublisher.publishStatusChanged(
                account.getId(),
                previousStatus.name(),
                command.targetStatus().name(),
                command.reason().name(),
                command.actorType(),
                command.actorId(),
                now
        );

        if (command.targetStatus() == AccountStatus.LOCKED) {
            eventPublisher.publishAccountLocked(
                    account.getId(), command.reason().name(),
                    command.actorType(), command.actorId(), now);
        } else if (command.targetStatus() == AccountStatus.ACTIVE
                && previousStatus == AccountStatus.LOCKED) {
            eventPublisher.publishAccountUnlocked(
                    account.getId(), command.reason().name(),
                    command.actorType(), command.actorId(), now);
        }
    }

    @Transactional
    public DeleteAccountResult deleteAccount(String accountId, StatusChangeReason reason,
                                              String actorType, String actorId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        AccountStatus previousStatus = account.getStatus();
        account.changeStatus(statusMachine, AccountStatus.DELETED, reason);

        accountRepository.save(account);

        AccountStatusHistoryEntry historyEntry = AccountStatusHistoryEntry.create(
                account.getId(),
                previousStatus,
                AccountStatus.DELETED,
                reason,
                actorType,
                actorId,
                null
        );
        historyRepository.save(historyEntry);

        Instant now = Instant.now();
        Instant gracePeriodEndsAt = now.plus(gracePeriodDays, ChronoUnit.DAYS);

        eventPublisher.publishStatusChanged(
                account.getId(), previousStatus.name(), AccountStatus.DELETED.name(),
                reason.name(), actorType, actorId, now);

        eventPublisher.publishAccountDeleted(
                account.getId(), reason.name(), actorType, actorId,
                now, gracePeriodEndsAt);

        return new DeleteAccountResult(
                account.getId(),
                previousStatus.name(),
                AccountStatus.DELETED.name(),
                gracePeriodEndsAt
        );
    }

}
