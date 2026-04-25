package com.example.account.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, String> {

    Optional<AccountJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT a FROM AccountJpaEntity a")
    Page<AccountJpaEntity> findAllAccounts(Pageable pageable);

    /**
     * Returns ACTIVE accounts whose last successful login (or creation date when never logged in)
     * occurred before the given threshold. Used by AccountDormantScheduler to drive the
     * 365-day ACTIVE → DORMANT transition (retention.md §1.3, §1.4).
     */
    @Query("SELECT a FROM AccountJpaEntity a " +
            "WHERE a.status = com.example.account.domain.status.AccountStatus.ACTIVE " +
            "  AND COALESCE(a.lastLoginSucceededAt, a.createdAt) < :threshold")
    List<AccountJpaEntity> findActiveDormantCandidates(@Param("threshold") Instant threshold);

    /**
     * Find DELETED accounts whose grace period (30 days, see retention.md §2)
     * has expired and whose profile has not yet been anonymized.
     *
     * @param threshold cut-off instant; rows with {@code deleted_at < threshold} are eligible.
     *                  Caller passes {@code Instant.now().minus(30, DAYS)}.
     */
    @Query("""
            SELECT a FROM AccountJpaEntity a
            LEFT JOIN ProfileJpaEntity p ON p.accountId = a.id
            WHERE a.status = com.example.account.domain.status.AccountStatus.DELETED
              AND a.deletedAt < :threshold
              AND (p.maskedAt IS NULL OR p.accountId IS NULL)
            """)
    List<AccountJpaEntity> findAnonymizationCandidates(@Param("threshold") Instant threshold);
}
