package com.example.account.presentation;

import com.example.account.infrastructure.config.SecurityConfig;
import com.example.account.infrastructure.persistence.AccountJpaEntity;
import com.example.account.infrastructure.persistence.AccountJpaRepository;
import com.example.account.infrastructure.persistence.ProfileJpaRepository;
import com.example.account.presentation.advice.GlobalExceptionHandler;
import com.example.account.presentation.internal.AccountSearchController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountSearchController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("AccountSearchController slice tests")
class AccountSearchControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AccountJpaRepository accountRepository;

    @MockitoBean
    ProfileJpaRepository profileRepository;

    @Test
    @DisplayName("GET /internal/accounts (no email) returns paginated list")
    void search_noEmail_returnsPaginatedList() throws Exception {
        var entity = mockEntity("acc-1", "a@example.com");
        var page = new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1);
        given(accountRepository.findAllAccounts(any(org.springframework.data.domain.Pageable.class))).willReturn(page);

        mockMvc.perform(get("/internal/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("acc-1"))
                .andExpect(jsonPath("$.content[0].email").value("a@example.com"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("GET /internal/accounts?page=1&size=5 returns correct page")
    void search_noEmail_pageAndSize_appliedCorrectly() throws Exception {
        var entity = mockEntity("acc-2", "b@example.com");
        var page = new PageImpl<>(List.of(entity), PageRequest.of(1, 5), 6);
        given(accountRepository.findAllAccounts(eq(PageRequest.of(1, 5)))).willReturn(page);

        mockMvc.perform(get("/internal/accounts?page=1&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("GET /internal/accounts?size=101 returns 400")
    void search_sizeOverMax_returns400() throws Exception {
        mockMvc.perform(get("/internal/accounts?size=101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /internal/accounts?email=a@example.com returns single item")
    void search_withEmail_returnsSingleItem() throws Exception {
        var entity = mockEntity("acc-1", "a@example.com");
        given(accountRepository.findByEmail("a@example.com")).willReturn(Optional.of(entity));

        mockMvc.perform(get("/internal/accounts?email=a@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("acc-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /internal/accounts?email=unknown returns empty content")
    void search_withUnknownEmail_returnsEmpty() throws Exception {
        given(accountRepository.findByEmail("unknown@example.com")).willReturn(Optional.empty());

        mockMvc.perform(get("/internal/accounts?email=unknown@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private AccountJpaEntity mockEntity(String id, String email) {
        var entity = org.mockito.Mockito.mock(AccountJpaEntity.class);
        org.mockito.Mockito.when(entity.getId()).thenReturn(id);
        org.mockito.Mockito.when(entity.getEmail()).thenReturn(email);
        org.mockito.Mockito.when(entity.getStatus()).thenReturn(
                com.example.account.domain.status.AccountStatus.ACTIVE);
        org.mockito.Mockito.when(entity.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }
}
