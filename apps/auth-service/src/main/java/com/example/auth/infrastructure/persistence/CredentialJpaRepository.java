package com.example.auth.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CredentialJpaRepository extends JpaRepository<CredentialJpaEntity, Long> {

    Optional<CredentialJpaEntity> findByAccountId(String accountId);
}
