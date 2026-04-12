package com.example.auth.application.command;

public record LogoutCommand(
        String refreshToken
) {
}
