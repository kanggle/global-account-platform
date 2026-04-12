package com.example.admin.presentation;

import com.example.admin.application.AuditQueryResult;
import com.example.admin.application.AuditQueryUseCase;
import com.example.admin.presentation.advice.AdminExceptionHandler;
import com.example.admin.support.OperatorJwtTestFixture;
import com.example.admin.support.SliceTestSecurityConfig;
import com.gap.security.jwt.JwtVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditController.class)
@Import({SliceTestSecurityConfig.class, AdminExceptionHandler.class,
        AuditControllerTest.JwtBeans.class})
class AuditControllerTest {

    private static OperatorJwtTestFixture jwt;

    @BeforeAll
    static void initFixture() {
        jwt = new OperatorJwtTestFixture();
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class JwtBeans {
        @Bean
        JwtVerifier operatorJwtVerifier() {
            if (jwt == null) jwt = new OperatorJwtTestFixture();
            return jwt.verifier();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AuditQueryUseCase useCase;

    private String bearer(List<String> roles) {
        return "Bearer " + jwt.operatorToken("op-1", roles);
    }

    private AuditQueryResult emptyResult() {
        return new AuditQueryResult(List.of(), 0, 20, 0L, 0);
    }

    @Test
    void audit_query_with_auditor_role_returns_200() throws Exception {
        when(useCase.query(any())).thenReturn(emptyResult());

        mockMvc.perform(get("/api/admin/audit")
                        .header("Authorization", bearer(List.of("AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void audit_query_with_accountAdmin_role_returns_200() throws Exception {
        when(useCase.query(any())).thenReturn(emptyResult());

        mockMvc.perform(get("/api/admin/audit")
                        .header("Authorization", bearer(List.of("ACCOUNT_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void audit_query_with_superAdmin_role_returns_200() throws Exception {
        when(useCase.query(any())).thenReturn(emptyResult());

        mockMvc.perform(get("/api/admin/audit")
                        .header("Authorization", bearer(List.of("SUPER_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void audit_query_without_jwt_returns_401_token_invalid() throws Exception {
        mockMvc.perform(get("/api/admin/audit"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }
}
