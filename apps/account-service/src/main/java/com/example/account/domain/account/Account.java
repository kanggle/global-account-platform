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
    private AccountStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
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
    public static Account reconstitute(String id, String email, AccountStatus status,
                                        Instant createdAt, Instant updatedAt,
                                        Instant deletedAt, int version) {
        Account account = new Account();
        account.id = id;
        account.email = email;
        account.status = status;
        account.createdAt = createdAt;
        account.updatedAt = updatedAt;
        account.deletedAt = deletedAt;
        account.version = version;
        return account;
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
