package com.example.auth.application.command;

/**
 * Command for creating a credential row in auth_db.credentials.
 *
 * <p>Issued by account-service during signup (TASK-BE-063) via the internal
 * endpoint {@code POST /internal/auth/credentials}. The password is accepted in
 * plain text on the internal boundary only; auth-service is responsible for
 * argon2id-hashing it before persistence and must never log it.</p>
 */
public record CreateCredentialCommand(
        String accountId,
        String email,
        String password
) {
}
