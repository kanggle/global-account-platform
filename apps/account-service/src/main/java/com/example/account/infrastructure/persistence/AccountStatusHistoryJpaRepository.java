package com.example.account.infrastructure.persistence;

import com.example.account.domain.history.AccountStatusHistoryEntry;
import com.example.account.domain.repository.AccountStatusHistoryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountStatusHistoryJpaRepository
        extends JpaRepository<AccountStatusHistoryEntry, Long>, AccountStatusHistoryRepository {

    @Override
    List<AccountStatusHistoryEntry> findByAccountIdOrderByOccurredAtDesc(String accountId);

    @Override
    Optional<AccountStatusHistoryEntry> findTopByAccountIdOrderByOccurredAtDesc(String accountId);
}
