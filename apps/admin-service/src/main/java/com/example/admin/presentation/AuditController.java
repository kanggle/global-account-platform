package com.example.admin.presentation;

import com.example.admin.application.AuditQueryUseCase;
import com.example.admin.application.QueryAuditCommand;
import com.example.admin.infrastructure.security.OperatorContextHolder;
import com.example.admin.presentation.dto.AuditQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryUseCase useCase;

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','ACCOUNT_ADMIN','SUPER_ADMIN')")
    public ResponseEntity<AuditQueryResponse> query(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator-Reason", required = false) String reason) {

        var cmd = new QueryAuditCommand(
                accountId, actionCode, from, to, source,
                page, size, idempotencyKey, reason,
                OperatorContextHolder.require());

        return ResponseEntity.ok(AuditQueryResponse.from(useCase.query(cmd)));
    }
}
