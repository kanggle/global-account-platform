package com.example.admin.infrastructure.persistence.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "admin_operators")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminOperatorJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    // External operator identifier (UUID v7). This is the value carried in the
    // operator JWT `sub` claim; the internal BIGINT `id` is never exposed.
    @Column(name = "operator_id", length = 36, nullable = false, unique = true)
    private String operatorId;

    @Column(name = "email", length = 255, nullable = false)
    private String email;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @Column(name = "display_name", length = 120, nullable = false)
    private String displayName;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    // Reserved for TASK-BE-029 (TOTP enrollment). NULL-only in this increment.
    @Column(name = "totp_enrolled_at")
    private Instant totpEnrolledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
