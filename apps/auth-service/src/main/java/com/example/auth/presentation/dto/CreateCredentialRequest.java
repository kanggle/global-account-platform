package com.example.auth.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /internal/auth/credentials} (internal only —
 * invoked by account-service during signup, never exposed through the gateway).
 */
public record CreateCredentialRequest(
        @NotBlank(message = "accountId is required")
        @Size(max = 36, message = "accountId must not exceed 36 characters")
        String accountId,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, message = "password must be at least 8 characters")
        String password
) {
}
