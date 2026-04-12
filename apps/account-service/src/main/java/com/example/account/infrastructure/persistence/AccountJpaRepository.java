package com.example.account.infrastructure.persistence;

import com.example.account.domain.account.Account;
import com.example.account.domain.repository.AccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountJpaRepository extends JpaRepository<Account, String>, AccountRepository {
}
