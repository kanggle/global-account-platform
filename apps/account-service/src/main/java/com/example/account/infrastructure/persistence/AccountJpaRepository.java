package com.example.account.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, String> {

    Optional<AccountJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
