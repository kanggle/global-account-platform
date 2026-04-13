package com.example.membership.infrastructure.persistence;

import com.example.membership.domain.plan.PlanLevel;
import com.example.membership.domain.subscription.Subscription;
import com.example.membership.domain.subscription.SubscriptionRepository;
import com.example.membership.domain.subscription.status.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SubscriptionRepositoryAdapter implements SubscriptionRepository {

    private final SubscriptionJpaRepository jpa;

    @Override
    public Subscription save(Subscription subscription) {
        return jpa.save(subscription);
    }

    @Override
    public Optional<Subscription> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public List<Subscription> findByAccountId(String accountId) {
        return jpa.findByAccountId(accountId);
    }

    @Override
    public Optional<Subscription> findActiveByAccountIdAndPlanLevel(String accountId, PlanLevel planLevel) {
        return jpa.findByAccountIdAndPlanLevelAndStatus(accountId, planLevel, SubscriptionStatus.ACTIVE);
    }

    @Override
    public List<Subscription> findActiveByAccountId(String accountId) {
        return jpa.findByAccountIdAndStatus(accountId, SubscriptionStatus.ACTIVE);
    }

    @Override
    public List<Subscription> findExpirable(SubscriptionStatus status, LocalDateTime cutoff, int limit) {
        return jpa.findExpirable(status, cutoff, PageRequest.of(0, limit));
    }
}
