package com.example.auth.infrastructure.client;

import com.example.auth.application.exception.AccountServiceUnavailableException;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.result.CredentialLookupResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Slf4j
@Component
public class AccountServiceClient implements AccountServicePort {

    private final RestClient restClient;

    public AccountServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${auth.account-service.base-url}") String baseUrl,
            @Value("${auth.account-service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${auth.account-service.read-timeout-ms:5000}") int readTimeoutMs) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public Optional<CredentialLookupResult> lookupCredentialsByEmail(String email) {
        try {
            CredentialLookupResult result = restClient.get()
                    .uri("/internal/accounts/credentials?email={email}", email)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        // 404 means no account found - not an error, just empty
                    })
                    .body(CredentialLookupResult.class);

            return Optional.ofNullable(result);
        } catch (Exception e) {
            if (is404(e)) {
                return Optional.empty();
            }
            log.error("Account service call failed: {}", e.getMessage());
            throw new AccountServiceUnavailableException("Account service is unavailable", e);
        }
    }

    private boolean is404(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("404");
    }
}
