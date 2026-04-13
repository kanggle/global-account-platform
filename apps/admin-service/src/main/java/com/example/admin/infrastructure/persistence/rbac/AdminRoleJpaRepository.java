package com.example.admin.infrastructure.persistence.rbac;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRoleJpaRepository extends JpaRepository<AdminRoleJpaEntity, Long> {
    Optional<AdminRoleJpaEntity> findByName(String name);
}
