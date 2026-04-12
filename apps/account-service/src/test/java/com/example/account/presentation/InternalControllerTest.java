package com.example.account.presentation;

import com.example.account.application.exception.AccountNotFoundException;
import com.example.account.application.result.AccountStatusResult;
import com.example.account.application.result.CredentialLookupResult;
import com.example.account.application.result.DeleteAccountResult;
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
@DisplayName("Internal Controller slice tests")
class InternalControllerTest {

    private static final String INTERNAL_TOKEN = "";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountStatusUseCase accountStatusUseCase;

    @Test
    @DisplayName("GET /internal/accounts/credentials returns credentialHash and hashAlgorithm fields")
    void lookupCredentials_validEmail_returns200WithAllFields() throws Exception {
        given(accountStatusUseCase.lookupByEmail(eq("test@example.com")))
                .willReturn(new CredentialLookupResult("acc-123", null, "none", "ACTIVE"));

        mockMvc.perform(get("/internal/accounts/credentials")
                        .param("email", "test@example.com")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc-123"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.hashAlgorithm").value("none"));
    }

    @Test
    @DisplayName("GET /internal/accounts/credentials unknown email returns 404")
    void lookupCredentials_unknownEmail_returns404() throws Exception {
        given(accountStatusUseCase.lookupByEmail(any()))
                .willThrow(new AccountNotFoundException("email"));

        mockMvc.perform(get("/internal/accounts/credentials")
                        .param("email", "unknown@example.com")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /internal/accounts/{id}/status returns 200")
    void getStatus_validId_returns200() throws Exception {
        given(accountStatusUseCase.getStatus(eq("acc-123")))
                .willReturn(new AccountStatusResult("acc-123", "ACTIVE", Instant.now(), null));

        mockMvc.perform(get("/internal/accounts/acc-123/status")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /internal/accounts/{id}/lock returns 200")
    void lockAccount_validRequest_returns200() throws Exception {
        given(accountStatusUseCase.changeStatus(any()))
                .willReturn(new StatusChangeResult("acc-123", "ACTIVE", "LOCKED", Instant.now()));

        mockMvc.perform(post("/internal/accounts/acc-123/lock")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
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
    @DisplayName("POST /internal/accounts/{id}/lock DELETED account returns 409")
    void lockAccount_deletedAccount_returns409() throws Exception {
        given(accountStatusUseCase.changeStatus(any()))
                .willThrow(new StateTransitionException(AccountStatus.DELETED, AccountStatus.LOCKED,
                        StatusChangeReason.ADMIN_LOCK));

        mockMvc.perform(post("/internal/accounts/acc-123/lock")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "ADMIN_LOCK",
                                  "operatorId": "op-1"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_TRANSITION_INVALID"));
    }

    @Test
    @DisplayName("POST /internal/accounts/{id}/unlock returns 200")
    void unlockAccount_validRequest_returns200() throws Exception {
        given(accountStatusUseCase.changeStatus(any()))
                .willReturn(new StatusChangeResult("acc-123", "LOCKED", "ACTIVE", Instant.now()));

        mockMvc.perform(post("/internal/accounts/acc-123/unlock")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
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

    @Test
    @DisplayName("POST /internal/accounts/{id}/delete returns 202")
    void deleteAccount_validRequest_returns202() throws Exception {
        Instant gracePeriodEndsAt = Instant.now().plusSeconds(30 * 24 * 3600L);
        given(accountStatusUseCase.deleteAccount(eq("acc-123"), eq(StatusChangeReason.ADMIN_DELETE),
                eq("operator"), eq("op-1")))
                .willReturn(new DeleteAccountResult("acc-123", "ACTIVE", "DELETED", gracePeriodEndsAt));

        mockMvc.perform(post("/internal/accounts/acc-123/delete")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "ADMIN_DELETE",
                                  "operatorId": "op-1"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accountId").value("acc-123"))
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.currentStatus").value("DELETED"))
                .andExpect(jsonPath("$.gracePeriodEndsAt").exists());
    }

    @Test
    @DisplayName("POST /internal/accounts/{id}/delete already DELETED returns 409")
    void deleteAccount_alreadyDeleted_returns409() throws Exception {
        given(accountStatusUseCase.deleteAccount(eq("acc-123"), eq(StatusChangeReason.ADMIN_DELETE),
                eq("operator"), eq("op-1")))
                .willThrow(new StateTransitionException(AccountStatus.DELETED, AccountStatus.DELETED,
                        StatusChangeReason.ADMIN_DELETE));

        mockMvc.perform(post("/internal/accounts/acc-123/delete")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "ADMIN_DELETE",
                                  "operatorId": "op-1"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_TRANSITION_INVALID"));
    }

    @Test
    @DisplayName("POST /internal/accounts/{id}/delete not found returns 404")
    void deleteAccount_notFound_returns404() throws Exception {
        given(accountStatusUseCase.deleteAccount(eq("acc-999"), eq(StatusChangeReason.ADMIN_DELETE),
                eq("operator"), eq("op-1")))
                .willThrow(new AccountNotFoundException("acc-999"));

        mockMvc.perform(post("/internal/accounts/acc-999/delete")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "ADMIN_DELETE",
                                  "operatorId": "op-1"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }
}
