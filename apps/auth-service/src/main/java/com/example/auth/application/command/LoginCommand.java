package com.example.auth.application.command;

import com.example.auth.domain.session.SessionContext;

public record LoginCommand(
        String email,
        String password,
        SessionContext sessionContext
) {
}
