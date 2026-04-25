package com.example.account.domain.account;

import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.AccountStatusMachine;
import com.example.account.domain.status.StatusChangeReason;
import com.example.account.domain.status.StatusTransition;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Aggregate root for account domain.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    private String id;
    private String email;
    private String emailHash;
    private AccountStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private Instant lastLoginSucceededAt;
    private int version;

    public static Account create(String email) {
        Email validatedEmail = new Email(email);

        Account account = new Account();
        account.id = AccountId.generate().value();
        account.email = validatedEmail.value();
        account.status = AccountStatus.ACTIVE;
        account.createdAt = Instant.now();
        account.updatedAt = Instant.now();
        account.version = 0;
        return account;
    }

    /**
     * Reconstitute an Account from persisted state. Used by infrastructure mappers.
     */
    public static Account reconstitute(String id, String email, String emailHash,
                                        AccountStatus status,
                                        Instant createdAt, Instant updatedAt,
                                        Instant deletedAt,
                                        Instant lastLoginSucceededAt,
                                        int version) {
        Account account = new Account();
        account.id = id;
        account.email = email;
        account.emailHash = emailHash;
        account.status = status;
        account.createdAt = createdAt;
        account.updatedAt = updatedAt;
        account.deletedAt = deletedAt;
        account.lastLoginSucceededAt = lastLoginSucceededAt;
        account.version = version;
        return account;
    }

    /**
     * Apply GDPR PII masking to the email field.
     * Replaces email with a non-reversible masked value and stores the SHA-256 hash.
     */
    public void maskEmail(String hashedEmail, String maskedEmail) {
        this.emailHash = hashedEmail;
        this.email = maskedEmail;
        this.updatedAt = Instant.now();
    }

    /**
     * Record a successful login at the given instant.
     *
     * <p>Uses max semantics — the field is only advanced if the new instant is
     * strictly more recent than the value already stored. This guards against
     * out-of-order delivery of {@code auth.login.succeeded} Kafka events
     * (replay, partition rebalance, redelivery) where an older event might
     * arrive after a newer one. See TASK-BE-103 / specs/contracts/events/auth-events.md.
     *
     * @param occurredAt the timestamp of the successful login (UTC); never null
     */
    public void recordLoginSuccess(Instant occurredAt) {
        if (this.lastLoginSucceededAt == null || occurredAt.isAfter(this.lastLoginSucceededAt)) {
            this.lastLoginSucceededAt = occurredAt;
        }
    }

    /**
     * Transition account status via the state machine.
     * Returns the validated transition for recording in history.
     */
    public StatusTransition changeStatus(AccountStatusMachine machine,
                                         AccountStatus targetStatus,
                                         StatusChangeReason reason) {
        StatusTransition transition = machine.transition(this.status, targetStatus, reason);

        if (this.status != targetStatus) {
            this.status = targetStatus;
            this.updatedAt = Instant.now();

            if (targetStatus == AccountStatus.DELETED) {
                this.deletedAt = Instant.now();
            } else if (this.deletedAt != null) {
                // Recovering from DELETED state
                this.deletedAt = null;
            }
        }

        return transition;
    }
}
