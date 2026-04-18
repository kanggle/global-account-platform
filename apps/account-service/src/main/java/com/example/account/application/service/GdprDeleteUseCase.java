package com.example.account.application.service;

import com.example.account.application.event.AccountEventPublisher;
import com.example.account.application.exception.AccountNotFoundException;
import com.example.account.application.result.GdprDeleteResult;
import com.example.account.domain.account.Account;
import com.example.account.domain.history.AccountStatusHistoryEntry;
import com.example.account.domain.profile.Profile;
import com.example.account.domain.repository.AccountRepository;
import com.example.account.domain.repository.AccountStatusHistoryRepository;
import com.example.account.domain.repository.ProfileRepository;
import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.AccountStatusMachine;
import com.example.account.domain.status.StatusChangeReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * GDPR/PIPA Right to Erasure use case.
 * Transitions account to DELETED status and immediately masks all PII.
 */
@Service
@RequiredArgsConstructor
public class GdprDeleteUseCase {

    private final AccountRepository accountRepository;
    private final ProfileRepository profileRepository;
    private final AccountStatusHistoryRepository historyRepository;
    private final AccountStatusMachine statusMachine;
    private final AccountEventPublisher eventPublisher;

    @Transactional
    public GdprDeleteResult execute(String accountId, String operatorId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        AccountStatus previousStatus = account.getStatus();

        // Transition to DELETED via state machine
        account.changeStatus(statusMachine, AccountStatus.DELETED, StatusChangeReason.REGULATED_DELETION);

        // Mask email: replace with hash-based value
        String emailHash = sha256(account.getEmail());
        String maskedEmail = "gdpr_" + emailHash + "@deleted.local";
        account.maskEmail(emailHash, maskedEmail);

        accountRepository.save(account);

        // Record status history
        AccountStatusHistoryEntry historyEntry = AccountStatusHistoryEntry.create(
                account.getId(),
                previousStatus,
                AccountStatus.DELETED,
                StatusChangeReason.REGULATED_DELETION,
                "operator",
                operatorId,
                "GDPR deletion with immediate PII masking"
        );
        historyRepository.save(historyEntry);

        // Mask profile PII
        Instant maskedAt = Instant.now();
        profileRepository.findByAccountId(accountId).ifPresent(profile -> {
            profile.maskPii();
            profileRepository.save(profile);
        });

        // Publish events
        Instant now = Instant.now();
        eventPublisher.publishStatusChanged(
                account.getId(), previousStatus.name(), AccountStatus.DELETED.name(),
                StatusChangeReason.REGULATED_DELETION.name(), "operator", operatorId, now);

        eventPublisher.publishAccountDeletedAnonymized(
                account.getId(), StatusChangeReason.REGULATED_DELETION.name(),
                "operator", operatorId, now);

        return new GdprDeleteResult(account.getId(), AccountStatus.DELETED.name(), emailHash, maskedAt);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
