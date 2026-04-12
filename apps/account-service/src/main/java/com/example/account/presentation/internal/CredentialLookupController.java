package com.example.account.presentation.internal;

import com.example.account.application.service.AccountStatusUseCase;
import com.example.account.presentation.dto.response.CredentialLookupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/accounts")
public class CredentialLookupController {

    private final AccountStatusUseCase accountStatusUseCase;

    @GetMapping("/credentials")
    public ResponseEntity<CredentialLookupResponse> lookupCredentials(
            @RequestParam("email") String email) {
        var result = accountStatusUseCase.lookupByEmail(email);
        return ResponseEntity.ok(new CredentialLookupResponse(
                result.accountId(),
                result.accountStatus()
        ));
    }
}
