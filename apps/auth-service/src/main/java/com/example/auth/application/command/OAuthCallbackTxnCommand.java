package com.example.auth.application.command;

import com.example.auth.domain.oauth.OAuthProvider;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.infrastructure.oauth.OAuthUserInfo;

/**
 * Input to {@link com.example.auth.application.OAuthLoginTransactionalStep}.
 *
 * <p>Carries the result of the external provider HTTP exchange so that the
 * transactional step only performs DB writes. External HTTP MUST have
 * completed before constructing this command.
 */
public record OAuthCallbackTxnCommand(
        OAuthProvider provider,
        OAuthUserInfo userInfo,
        SessionContext sessionContext
) {
}
