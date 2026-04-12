package com.example.account.domain.history;

import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.StatusChangeReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "account_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountStatusHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private AccountStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private AccountStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 50)
    private StatusChangeReason reasonCode;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", length = 36)
    private String actorId;

    @Column(columnDefinition = "JSON")
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static AccountStatusHistoryEntry create(String accountId,
                                                    AccountStatus fromStatus,
                                                    AccountStatus toStatus,
                                                    StatusChangeReason reasonCode,
                                                    String actorType,
                                                    String actorId,
                                                    String details) {
        AccountStatusHistoryEntry entry = new AccountStatusHistoryEntry();
        entry.accountId = accountId;
        entry.fromStatus = fromStatus;
        entry.toStatus = toStatus;
        entry.reasonCode = reasonCode;
        entry.actorType = actorType;
        entry.actorId = actorId;
        entry.details = details;
        entry.occurredAt = Instant.now();
        return entry;
    }
}
