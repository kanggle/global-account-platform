package com.example.account.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, String> {

    Optional<AccountJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT a FROM AccountJpaEntity a")
    Page<AccountJpaEntity> findAllAccounts(Pageable pageable);
}
