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
     * Find DELETED accounts whose grace period (30 days, see retention.md §2)
     * has expired and whose profile has not yet been anonymized.
     *
     * <p>Mirrors retention.md §2.4:
     * <pre>
     * SELECT a.*
     *   FROM accounts a
     *   LEFT JOIN profiles p ON p.account_id = a.id
     *  WHERE a.status = 'DELETED'
     *    AND a.deleted_at &lt; :threshold
     *    AND (p.masked_at IS NULL OR p.account_id IS NULL);
     * </pre>
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
