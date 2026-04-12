package com.example.admin.application;

import com.example.admin.application.event.AdminEventPublisher;
import com.example.admin.application.exception.AuditFailureException;
import com.example.admin.infrastructure.persistence.AdminActionJpaEntity;
import com.example.admin.infrastructure.persistence.AdminActionJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class AdminActionAuditorTest {

    @Mock
    AdminActionJpaRepository repo;

    @Mock
    AdminEventPublisher publisher;

    @InjectMocks
    AdminActionAuditor auditor;

    @Test
    void record_propagates_db_failure_as_audit_failure() {
        doThrow(new RuntimeException("db down")).when(repo).save(any());

        OperatorContext op = new OperatorContext("op-1", Set.of(OperatorRole.ACCOUNT_ADMIN));
        AdminActionAuditor.AuditRecord rec = new AdminActionAuditor.AuditRecord(
                "audit-1", ActionCode.ACCOUNT_LOCK, op,
                "account", "acc-1", "fraud", null, "idemp",
                Outcome.SUCCESS, null, Instant.now(), Instant.now());

        assertThatThrownBy(() -> auditor.record(rec))
                .isInstanceOf(AuditFailureException.class);

        verify(publisher, never()).publishActionPerformed(any());
    }

    @Test
    void record_saves_entity_and_publishes_event() {
        OperatorContext op = new OperatorContext("op-1", Set.of(OperatorRole.ACCOUNT_ADMIN));
        AdminActionAuditor.AuditRecord rec = new AdminActionAuditor.AuditRecord(
                "audit-1", ActionCode.ACCOUNT_LOCK, op,
                "account", "acc-1", "fraud", null, "idemp",
                Outcome.SUCCESS, null, Instant.now(), Instant.now());

        auditor.record(rec);

        verify(repo).save(any(AdminActionJpaEntity.class));
        verify(publisher).publishActionPerformed(rec);
    }
}
