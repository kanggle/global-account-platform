package com.example.admin.infrastructure.persistence.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "admin_operator_roles")
@IdClass(AdminOperatorRoleJpaEntity.PK.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminOperatorRoleJpaEntity {

    @Id
    @Column(name = "operator_id", length = 36, nullable = false)
    private String operatorId;

    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", length = 36)
    private String grantedBy;

    public static AdminOperatorRoleJpaEntity create(String operatorId, Long roleId,
                                                    Instant grantedAt, String grantedBy) {
        AdminOperatorRoleJpaEntity e = new AdminOperatorRoleJpaEntity();
        e.operatorId = operatorId;
        e.roleId = roleId;
        e.grantedAt = grantedAt;
        e.grantedBy = grantedBy;
        return e;
    }

    public static class PK implements Serializable {
        private String operatorId;
        private Long roleId;
        public PK() {}
        public PK(String operatorId, Long roleId) {
            this.operatorId = operatorId;
            this.roleId = roleId;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(operatorId, pk.operatorId)
                    && Objects.equals(roleId, pk.roleId);
        }
        @Override public int hashCode() { return Objects.hash(operatorId, roleId); }
    }
}
