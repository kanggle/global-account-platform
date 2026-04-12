package com.example.security.query.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Placeholder controller for suspicious events query.
 * Returns empty results until detection rules are implemented (TASK-BE-011).
 */
@RestController
@RequestMapping("/internal/security")
@RequiredArgsConstructor
public class SuspiciousEventQueryController {

    @GetMapping("/suspicious-events")
    public ResponseEntity<?> getSuspiciousEvents(
            @RequestParam String accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String ruleCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", Collections.emptyList());
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", 0);
        response.put("totalPages", 0);

        return ResponseEntity.ok(response);
    }
}
