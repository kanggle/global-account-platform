package com.example.account.domain.repository;

import com.example.account.domain.account.Account;

import java.util.Optional;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(String id);

    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);
}
