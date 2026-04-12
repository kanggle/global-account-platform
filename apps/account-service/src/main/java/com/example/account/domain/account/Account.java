package com.example.account.domain.account;

import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.AccountStatusMachine;
import com.example.account.domain.status.StatusChangeReason;
import com.example.account.domain.status.StatusTransition;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Aggregate root for account domain.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
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
