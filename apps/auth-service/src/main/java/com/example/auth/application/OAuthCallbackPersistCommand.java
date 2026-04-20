package com.example.auth.application;

import com.example.auth.domain.session.SessionContext;
import com.example.auth.infrastructure.oauth.OAuthUserInfo;

/**
 * Internal command carrying the already-resolved inputs to the persistence
 * step of the OAuth login callback. Produced by {@link OAuthLoginUseCase}
 * after all external HTTP calls (OAuth provider + account-service) finish,
 * and consumed by {@link OAuthLoginTransactionalStep#persist}.
 */
public record OAuthCallbackPersistCommand(
        OAuthUserInfo userInfo,
        String accountId,
        boolean newAccount,
        SessionContext sessionContext
) {
}
