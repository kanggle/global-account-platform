package com.example.auth.application.port;

import com.example.auth.application.result.CredentialLookupResult;
import com.example.auth.application.result.SocialSignupResult;

import java.util.Optional;

/**
 * Port interface for communicating with account-service.
 * Implementation lives in infrastructure/client/.
 */
public interface AccountServicePort {

    /**
     * Looks up credentials by email via internal HTTP to account-service.
     *
     * @return credential info including account status, or empty if no account found
     * @throws com.example.auth.application.exception.AccountServiceUnavailableException if account-service is down
     */
    Optional<CredentialLookupResult> lookupCredentialsByEmail(String email);

    /**
     * Creates or retrieves an account for social login via internal HTTP to account-service.
     * If an account with the given email already exists, returns the existing accountId.
     * If not, creates a new account and returns the new accountId.
     *
     * @param email          the user's email from the OAuth provider
     * @param provider       the OAuth provider name (e.g., "GOOGLE", "KAKAO")
     * @param providerUserId the user's unique ID from the OAuth provider
     * @param displayName    the user's display name from the OAuth provider (nullable)
     * @return social signup result with accountId, status, and whether it's a new account
     * @throws com.example.auth.application.exception.AccountServiceUnavailableException if account-service is down
     */
    SocialSignupResult socialSignup(String email, String provider, String providerUserId, String displayName);
}
