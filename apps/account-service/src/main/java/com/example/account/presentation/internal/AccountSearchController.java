package com.example.account.presentation.internal;

import com.example.account.infrastructure.persistence.AccountJpaRepository;
import com.example.account.infrastructure.persistence.ProfileJpaRepository;
import com.example.account.presentation.dto.response.AccountDetailResponse;
import com.example.account.presentation.dto.response.AccountSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/accounts")
public class AccountSearchController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountJpaRepository accountRepository;
    private final ProfileJpaRepository profileRepository;

    @GetMapping
    public ResponseEntity<AccountSearchResponse> search(
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be ≤ " + MAX_PAGE_SIZE);
        }

        if (email == null || email.isBlank()) {
            Page<com.example.account.infrastructure.persistence.AccountJpaEntity> result =
                    accountRepository.findAllAccounts(PageRequest.of(page, size));
            List<AccountSearchResponse.Item> items = result.getContent().stream()
                    .map(e -> new AccountSearchResponse.Item(
                            e.getId(), e.getEmail(), e.getStatus().name(), e.getCreatedAt()))
                    .toList();
            return ResponseEntity.ok(new AccountSearchResponse(
                    items, result.getTotalElements(), result.getNumber(),
                    result.getSize(), result.getTotalPages()));
        }

        return accountRepository.findByEmail(email.trim())
                .map(e -> {
                    var item = new AccountSearchResponse.Item(
                            e.getId(), e.getEmail(), e.getStatus().name(), e.getCreatedAt());
                    return ResponseEntity.ok(new AccountSearchResponse(List.of(item), 1, 0, size, 1));
                })
                .orElseGet(() -> ResponseEntity.ok(new AccountSearchResponse(List.of(), 0, 0, size, 0)));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountDetailResponse> detail(@PathVariable String accountId) {
        return accountRepository.findById(accountId)
                .map(account -> {
                    var profile = profileRepository.findByAccountId(accountId).orElse(null);
                    return ResponseEntity.ok(AccountDetailResponse.of(account, profile));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
