package com.example.admin.application;

import com.example.admin.application.exception.DownstreamFailureException;
import com.example.admin.application.exception.PermissionDeniedException;
import com.example.admin.application.exception.ReasonRequiredException;
import com.example.admin.infrastructure.client.AuthServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class SessionAdminUseCase {

    private final AuthServiceClient authServiceClient;
    private final AdminActionAuditor auditor;

    public RevokeSessionResult revoke(RevokeSessionCommand cmd) {
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw new ReasonRequiredException();
        }
        if (!cmd.operator().hasRole(OperatorRole.ACCOUNT_ADMIN)) {
            throw new PermissionDeniedException("operator lacks role ACCOUNT_ADMIN");
        }

        String auditId = auditor.reserveAuditId();
        Instant startedAt = Instant.now();
        AuthServiceClient.ForceLogoutResponse downstream;
        try {
            downstream = authServiceClient.forceLogout(
                    cmd.accountId(),
                    cmd.operator().operatorId(),
                    cmd.reason(),
                    cmd.idempotencyKey());
        } catch (DownstreamFailureException ex) {
            auditor.record(new AdminActionAuditor.AuditRecord(
                    auditId, ActionCode.SESSION_REVOKE, cmd.operator(),
                    "account", cmd.accountId(),
                    cmd.reason(), null, cmd.idempotencyKey(),
                    Outcome.FAILURE, ex.getMessage(),
                    startedAt, Instant.now()));
            throw ex;
        }

        Instant completedAt = Instant.now();
        auditor.record(new AdminActionAuditor.AuditRecord(
                auditId, ActionCode.SESSION_REVOKE, cmd.operator(),
                "account", cmd.accountId(),
                cmd.reason(), null, cmd.idempotencyKey(),
                Outcome.SUCCESS, null, startedAt, completedAt));

        int count = downstream.revokedTokenCount() == null ? 0 : downstream.revokedTokenCount();
        return new RevokeSessionResult(
                downstream.accountId(),
                count,
                cmd.operator().operatorId(),
                downstream.revokedAt() != null ? downstream.revokedAt() : completedAt,
                auditId);
    }
}
