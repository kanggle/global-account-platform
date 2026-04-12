package com.example.security.query.internal;

import com.example.security.query.SecurityQueryService;
import com.example.security.query.dto.SuspiciousEventView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only suspicious events query endpoint. admin-service only.
 * Response PII-safe: evidence is rule-supplied (no raw IP/email) and we do not
 * echo the trigger IP here.
 */
@RestController
@RequestMapping("/internal/security")
@RequiredArgsConstructor
public class SuspiciousEventQueryController {

    private final SecurityQueryService queryService;

    @GetMapping("/suspicious-events")
    public ResponseEntity<?> getSuspiciousEvents(
            @RequestParam String accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String ruleCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Instant fromInstant = from == null ? Instant.EPOCH : Instant.parse(from);
        Instant toInstant = to == null ? Instant.now().plusSeconds(60) : Instant.parse(to);

        List<SuspiciousEventView> all = queryService.findSuspiciousEvents(accountId, fromInstant, toInstant);
        if (ruleCode != null && !ruleCode.isBlank()) {
            all = all.stream().filter(v -> ruleCode.equals(v.ruleCode())).toList();
        }

        int total = all.size();
        int fromIdx = Math.min(page * size, total);
        int toIdx = Math.min(fromIdx + size, total);
        List<SuspiciousEventView> content = all.subList(fromIdx, toIdx);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", total);
        response.put("totalPages", size == 0 ? 0 : (int) Math.ceil((double) total / size));

        return ResponseEntity.ok(response);
    }
}
