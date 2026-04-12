package com.example.security.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SuspiciousEventJpaRepository extends JpaRepository<SuspiciousEventJpaEntity, String> {

    List<SuspiciousEventJpaEntity> findByAccountIdAndDetectedAtBetweenOrderByDetectedAtDesc(
            String accountId, Instant from, Instant to);
}
