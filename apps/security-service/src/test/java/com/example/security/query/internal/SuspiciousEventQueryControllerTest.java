package com.example.security.query.internal;

import com.example.security.infrastructure.config.InternalAuthFilter;
import com.example.security.query.SecurityQueryService;
import com.example.security.query.dto.SuspiciousEventView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link SuspiciousEventQueryController}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>internal token authentication via {@link InternalAuthFilter}</li>
 *   <li>read-only query response shape (PII-safe — evidence is rule-supplied, no raw IP/email)</li>
 *   <li>error mapping through {@link QueryExceptionHandler} for missing params and type mismatch</li>
 *   <li>size cap at 100</li>
 *   <li>ruleCode filter passthrough to service</li>
 * </ul>
 */
@WebMvcTest(controllers = SuspiciousEventQueryController.class)
@Import({QueryExceptionHandler.class, InternalAuthFilter.class})
@ActiveProfiles("test")
class SuspiciousEventQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SecurityQueryService queryService;

    private static final String TOKEN = "test-internal-token";

    @Test
    @DisplayName("accountId 파라미터가 누락되면 400 VALIDATION_ERROR 응답을 반환한다")
    void getSuspiciousEvents_missingAccountId_returns400() throws Exception {
        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Missing required parameter: accountId"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("X-Internal-Token 헤더가 없으면 403 PERMISSION_DENIED 응답을 반환한다")
    void getSuspiciousEvents_missingInternalToken_returns403() throws Exception {
        mockMvc.perform(get("/internal/security/suspicious-events")
                        .param("accountId", "acc-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("X-Internal-Token 헤더 값이 잘못되면 403 PERMISSION_DENIED 응답을 반환한다")
    void getSuspiciousEvents_invalidInternalToken_returns403() throws Exception {
        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", "wrong-token")
                        .param("accountId", "acc-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("서비스가 IllegalArgumentException을 던지면 400 VALIDATION_ERROR 응답을 반환한다")
    void getSuspiciousEvents_serviceThrowsIllegalArgument_returns400() throws Exception {
        when(queryService.findSuspiciousEvents(eq("acc-001"), any(Instant.class), any(Instant.class), isNull(), any()))
                .thenThrow(new IllegalArgumentException("from must be before to"));

        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN)
                        .param("accountId", "acc-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("from must be before to"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("page 파라미터가 정수가 아니면 400 VALIDATION_ERROR 응답을 반환한다")
    void getSuspiciousEvents_pageParameterTypeMismatch_returns400() throws Exception {
        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN)
                        .param("accountId", "acc-001")
                        .param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter: page"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("인증된 정상 요청은 200으로 PII-safe 응답 본문을 반환한다")
    void getSuspiciousEvents_authenticatedRequest_returns200WithPiiSafeBody() throws Exception {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("previousCountry", "KR");
        evidence.put("currentCountry", "US");
        evidence.put("timeDeltaMinutes", 30);

        SuspiciousEventView view = new SuspiciousEventView(
                "evt-1", "acc-001", "GEO_ANOMALY", 85, "AUTO_LOCK",
                evidence, Instant.parse("2026-04-12T10:00:00Z"));

        when(queryService.findSuspiciousEvents(eq("acc-001"), any(Instant.class), any(Instant.class), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN)
                        .param("accountId", "acc-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("evt-1"))
                .andExpect(jsonPath("$.content[0].accountId").value("acc-001"))
                .andExpect(jsonPath("$.content[0].ruleCode").value("GEO_ANOMALY"))
                .andExpect(jsonPath("$.content[0].riskScore").value(85))
                .andExpect(jsonPath("$.content[0].actionTaken").value("AUTO_LOCK"))
                .andExpect(jsonPath("$.content[0].evidence.previousCountry").value("KR"))
                .andExpect(jsonPath("$.content[0].evidence.currentCountry").value("US"))
                .andExpect(jsonPath("$.content[0].ip").doesNotExist())
                .andExpect(jsonPath("$.content[0].ipAddress").doesNotExist())
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("size가 100을 초과하면 100으로 캡 처리된다")
    void getSuspiciousEvents_sizeOver100_isCappedAt100() throws Exception {
        when(queryService.findSuspiciousEvents(eq("acc-001"), any(), any(), isNull(),
                argThat(p -> p.getPageSize() == 100)))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 100), 0));

        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN)
                        .param("accountId", "acc-001")
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("ruleCode 파라미터로 결과를 필터링하여 200 응답을 반환한다")
    void getSuspiciousEvents_filterByRuleCode_returnsOnlyMatchingEvents() throws Exception {
        SuspiciousEventView geoEvent = new SuspiciousEventView(
                "evt-1", "acc-001", "GEO_ANOMALY", 85, "AUTO_LOCK",
                Map.of(), Instant.parse("2026-04-12T10:00:00Z"));

        when(queryService.findSuspiciousEvents(eq("acc-001"), any(Instant.class), any(Instant.class),
                eq("GEO_ANOMALY"), any()))
                .thenReturn(new PageImpl<>(List.of(geoEvent), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN)
                        .param("accountId", "acc-001")
                        .param("ruleCode", "GEO_ANOMALY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("evt-1"))
                .andExpect(jsonPath("$.content[0].ruleCode").value("GEO_ANOMALY"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
