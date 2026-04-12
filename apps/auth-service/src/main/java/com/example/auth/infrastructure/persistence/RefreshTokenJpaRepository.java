package com.example.auth.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {

    Optional<RefreshTokenJpaEntity> findByJti(String jti);

    boolean existsByRotatedFrom(String jti);

    @Modifying
    @Query("UPDATE RefreshTokenJpaEntity r SET r.revoked = true WHERE r.accountId = :accountId AND r.revoked = false")
    void revokeAllByAccountId(@Param("accountId") String accountId);
}
