package com.example.security.query.internal;

import com.example.security.infrastructure.config.InternalAuthFilter;
import com.example.security.query.SecurityQueryService;
import com.example.security.query.dto.SuspiciousEventView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SuspiciousEventQueryController.class)
@Import({QueryExceptionHandler.class, InternalAuthFilter.class})
class SuspiciousEventQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SecurityQueryService queryService;

    private static final String TOKEN = "test-internal-token";

    @Test
    @DisplayName("Returns 400 VALIDATION_ERROR when accountId is missing")
    void missingAccountIdReturns400() throws Exception {
        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Missing required parameter: accountId"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Returns 403 PERMISSION_DENIED when X-Internal-Token is missing")
    void missingTokenReturns403() throws Exception {
        mockMvc.perform(get("/internal/security/suspicious-events")
                        .param("accountId", "acc-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Returns 403 PERMISSION_DENIED when X-Internal-Token is invalid")
    void invalidTokenReturns403() throws Exception {
        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", "wrong-token")
                        .param("accountId", "acc-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("Returns 200 with paginated suspicious events when authenticated")
    void authenticatedRequestReturns200() throws Exception {
        SuspiciousEventView view = new SuspiciousEventView(
                "evt-001", "acc-001", "VELOCITY_ABUSE", 80,
                "ALERT", Map.of("count", 10), Instant.parse("2026-04-12T10:00:00Z"));

        when(queryService.findSuspiciousEvents(eq("acc-001"), any(), any(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN)
                        .param("accountId", "acc-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("evt-001"))
                .andExpect(jsonPath("$.content[0].ruleCode").value("VELOCITY_ABUSE"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("Caps size at 100 when size > 100 is requested")
    void sizeIsCappedAt100() throws Exception {
        when(queryService.findSuspiciousEvents(eq("acc-001"), any(), any(), isNull(), argThat(p -> p.getPageSize() == 100)))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 100), 0));

        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN)
                        .param("accountId", "acc-001")
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("Returns filtered results when ruleCode is provided")
    void ruleCodeFilterIsPassed() throws Exception {
        SuspiciousEventView view = new SuspiciousEventView(
                "evt-002", "acc-001", "GEO_ANOMALY", 60,
                "ALERT", Collections.emptyMap(), Instant.parse("2026-04-12T11:00:00Z"));

        when(queryService.findSuspiciousEvents(eq("acc-001"), any(), any(), eq("GEO_ANOMALY"), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/internal/security/suspicious-events")
                        .header("X-Internal-Token", TOKEN)
                        .param("accountId", "acc-001")
                        .param("ruleCode", "GEO_ANOMALY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ruleCode").value("GEO_ANOMALY"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
