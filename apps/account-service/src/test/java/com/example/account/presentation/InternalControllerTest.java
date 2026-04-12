package com.example.account.presentation;

import com.example.account.application.exception.AccountNotFoundException;
import com.example.account.application.result.AccountStatusResult;
import com.example.account.application.result.StatusChangeResult;
import com.example.account.application.service.AccountStatusUseCase;
import com.example.account.domain.status.AccountStatus;
import com.example.account.domain.status.StateTransitionException;
import com.example.account.domain.status.StatusChangeReason;
import com.example.account.infrastructure.config.SecurityConfig;
import com.example.account.presentation.advice.GlobalExceptionHandler;
import com.example.account.presentation.internal.AccountLockController;
import com.example.account.presentation.internal.AccountStatusQueryController;
import com.example.account.presentation.internal.CredentialLookupController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({CredentialLookupController.class, AccountStatusQueryController.class, AccountLockController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("Internal Controller 슬라이스 테스트")
class InternalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountStatusUseCase accountStatusUseCase;

    @Test
    @DisplayName("GET /internal/accounts/credentials 이메일로 조회 성공")
    void lookupCredentials_validEmail_returns200() throws Exception {
        given(accountStatusUseCase.lookupByEmail(eq("test@example.com")))
                .willReturn(new AccountStatusUseCase.CredentialLookupResult("acc-123", "ACTIVE"));

        mockMvc.perform(get("/internal/accounts/credentials")
                        .param("email", "test@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc-123"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /internal/accounts/credentials 존재하지 않는 이메일 404")
    void lookupCredentials_unknownEmail_returns404() throws Exception {
        given(accountStatusUseCase.lookupByEmail(any()))
                .willThrow(new AccountNotFoundException("unknown@example.com"));

        mockMvc.perform(get("/internal/accounts/credentials")
                        .param("email", "unknown@example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /internal/accounts/{id}/status 성공")
    void getStatus_validId_returns200() throws Exception {
        given(accountStatusUseCase.getStatus(eq("acc-123")))
                .willReturn(new AccountStatusResult("acc-123", "ACTIVE", Instant.now(), null));

        mockMvc.perform(get("/internal/accounts/acc-123/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /internal/accounts/{id}/lock 성공")
    void lockAccount_validRequest_returns200() throws Exception {
        given(accountStatusUseCase.changeStatus(any()))
                .willReturn(new StatusChangeResult("acc-123", "ACTIVE", "LOCKED", Instant.now()));

        mockMvc.perform(post("/internal/accounts/acc-123/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "ADMIN_LOCK",
                                  "operatorId": "op-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.currentStatus").value("LOCKED"));
    }

    @Test
    @DisplayName("POST /internal/accounts/{id}/lock DELETED 계정 잠금 시 400")
    void lockAccount_deletedAccount_returns400() throws Exception {
        given(accountStatusUseCase.changeStatus(any()))
                .willThrow(new StateTransitionException(AccountStatus.DELETED, AccountStatus.LOCKED,
                        StatusChangeReason.ADMIN_LOCK));

        mockMvc.perform(post("/internal/accounts/acc-123/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "ADMIN_LOCK",
                                  "operatorId": "op-1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATE_TRANSITION_INVALID"));
    }

    @Test
    @DisplayName("POST /internal/accounts/{id}/unlock 성공")
    void unlockAccount_validRequest_returns200() throws Exception {
        given(accountStatusUseCase.changeStatus(any()))
                .willReturn(new StatusChangeResult("acc-123", "LOCKED", "ACTIVE", Instant.now()));

        mockMvc.perform(post("/internal/accounts/acc-123/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "ADMIN_UNLOCK",
                                  "operatorId": "op-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousStatus").value("LOCKED"))
                .andExpect(jsonPath("$.currentStatus").value("ACTIVE"));
    }
}
