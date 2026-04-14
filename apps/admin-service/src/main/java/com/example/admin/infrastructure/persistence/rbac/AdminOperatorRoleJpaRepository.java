package com.example.admin.infrastructure.persistence.rbac;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminOperatorRoleJpaRepository
        extends JpaRepository<AdminOperatorRoleJpaEntity, AdminOperatorRoleJpaEntity.PK> {

    List<AdminOperatorRoleJpaEntity> findByOperatorId(Long operatorId);
}
