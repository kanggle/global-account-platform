package com.example.admin.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminOperatorTotpJpaRepository extends JpaRepository<AdminOperatorTotpJpaEntity, Long> {
}
