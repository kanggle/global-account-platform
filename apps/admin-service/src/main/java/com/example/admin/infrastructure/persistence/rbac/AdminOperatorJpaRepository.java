package com.example.admin.infrastructure.persistence.rbac;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminOperatorJpaRepository extends JpaRepository<AdminOperatorJpaEntity, String> {
    Optional<AdminOperatorJpaEntity> findByEmail(String email);
}
