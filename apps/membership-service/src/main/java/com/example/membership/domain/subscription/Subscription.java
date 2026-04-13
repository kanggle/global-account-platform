package com.example.membership.domain.subscription;

import com.example.membership.domain.plan.PlanLevel;
import com.example.membership.domain.subscription.status.SubscriptionStateTransitionException;
import com.example.membership.domain.subscription.status.SubscriptionStatus;
import com.example.membership.domain.subscription.status.SubscriptionStatusMachine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Subscription aggregate root. State transitions must go through
 * {@link SubscriptionStatusMachine} — direct UPDATE of status is forbidden.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_level", nullable = false, length = 20)
    private PlanLevel planLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(nullable = false)
    private int version;

    /**
     * Factory: creates a new ACTIVE subscription (transitions NONE → ACTIVE).
     * For FREE plan, expiresAt is null (permanent, scheduler-exempt).
     */
    public static Subscription activate(String accountId,
                                        PlanLevel planLevel,
                                        int durationDays,
                                        LocalDateTime now,
                                        SubscriptionStatusMachine machine) {
        machine.transition(SubscriptionStatus.NONE, SubscriptionStatus.ACTIVE);

        Subscription s = new Subscription();
        s.id = UUID.randomUUID().toString();
        s.accountId = accountId;
        s.planLevel = planLevel;
        s.status = SubscriptionStatus.ACTIVE;
        s.startedAt = now;
        s.expiresAt = (planLevel == PlanLevel.FREE || durationDays <= 0)
                ? null
                : now.plusDays(durationDays);
        s.createdAt = now;
        return s;
    }

    public void expire(LocalDateTime now, SubscriptionStatusMachine machine) {
        machine.transition(this.status, SubscriptionStatus.EXPIRED);
        this.status = SubscriptionStatus.EXPIRED;
        if (this.expiresAt == null) {
            this.expiresAt = now;
        }
    }

    public void cancel(LocalDateTime now, SubscriptionStatusMachine machine) {
        machine.transition(this.status, SubscriptionStatus.CANCELLED);
        this.status = SubscriptionStatus.CANCELLED;
        this.cancelledAt = now;
    }

    public boolean isActive() {
        return this.status == SubscriptionStatus.ACTIVE;
    }

    /**
     * Ownership check: returns true if this subscription belongs to the given account.
     */
    public boolean belongsTo(String candidateAccountId) {
        return this.accountId.equals(candidateAccountId);
    }

    /**
     * Test/reconstitution-only hook for backfilling an expired-at in the past. Keep package-private
     * so only integration tests within the same module reach it.
     */
    public void unsafeSetExpiresAtForTest(LocalDateTime value) {
        this.expiresAt = value;
    }
}
