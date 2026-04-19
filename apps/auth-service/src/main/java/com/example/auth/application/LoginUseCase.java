package com.example.auth.application;

import com.example.auth.application.command.LoginCommand;
import com.example.auth.application.event.AuthEventPublisher;
import com.example.auth.application.exception.AccountLockedException;
import com.example.auth.application.exception.AccountStatusException;
import com.example.auth.application.exception.CredentialsInvalidException;
import com.example.auth.application.exception.LoginRateLimitedException;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.port.TokenGeneratorPort;
import com.example.auth.application.result.AccountStatusLookupResult;
import com.example.auth.application.result.LoginResult;
import com.example.auth.application.result.RegisterDeviceSessionResult;
import com.example.auth.domain.credentials.Credential;
import com.example.auth.domain.repository.CredentialRepository;
import com.example.auth.domain.repository.LoginAttemptCounter;
import com.example.auth.domain.repository.RefreshTokenRepository;
import com.example.auth.domain.session.SessionContext;
import com.example.auth.domain.token.RefreshToken;
import com.example.auth.domain.token.TokenPair;
import com.gap.security.password.PasswordHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginUseCase {

    private final CredentialRepository credentialRepository;
    private final AccountServicePort accountServicePort;
    private final PasswordHasher passwordHasher;
    private final TokenGeneratorPort tokenGeneratorPort;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAttemptCounter loginAttemptCounter;
    private final AuthEventPublisher authEventPublisher;
    private final RegisterOrUpdateDeviceSessionUseCase registerOrUpdateDeviceSessionUseCase;

    @Value("${auth.login.max-failure-count:5}")
    private int maxFailureCount;

    @Transactional
    public LoginResult execute(LoginCommand command) {
        String emailHash = hashEmail(command.email());
        SessionContext ctx = command.sessionContext();

        // Check rate limit first
        int failCount = loginAttemptCounter.getFailureCount(emailHash);
        if (failCount >= maxFailureCount) {
            authEventPublisher.publishLoginAttempted(null, emailHash, ctx);
            authEventPublisher.publishLoginFailed(null, emailHash, "RATE_LIMITED", failCount, ctx);
            throw new LoginRateLimitedException();
        }

        // Publish login attempted event
        authEventPublisher.publishLoginAttempted(null, emailHash, ctx);

        // TASK-BE-063: Credential lookup is now local. auth_db.credentials owns
        // both credential_hash and the email lookup index, so we no longer need
        // to call account-service for password verification.
        Optional<Credential> credentialOpt = credentialRepository.findByAccountIdEmail(command.email());
        if (credentialOpt.isEmpty()) {
            loginAttemptCounter.incrementFailureCount(emailHash);
            int newCount = loginAttemptCounter.getFailureCount(emailHash);
            authEventPublisher.publishLoginFailed(null, emailHash, "CREDENTIALS_INVALID", newCount, ctx);
            throw new CredentialsInvalidException();
        }
        Credential credential = credentialOpt.get();
        String accountId = credential.getAccountId();

        // Short-lived null-guard ("단기 패치" from TASK-BE-063): if a credential
        // row somehow carries a null hash (e.g. partial legacy row), treat it as
        // invalid credentials rather than NPE-ing into 500 INTERNAL_ERROR.
        // With V0006 enforcing NOT NULL on credential_hash this is belt-and-suspenders.
        if (credential.getCredentialHash() == null) {
            log.warn("Credential row has null hash for accountId={}; treating as invalid", accountId);
            loginAttemptCounter.incrementFailureCount(emailHash);
            int newCount = loginAttemptCounter.getFailureCount(emailHash);
            authEventPublisher.publishLoginFailed(accountId, emailHash, "CREDENTIALS_INVALID", newCount, ctx);
            throw new CredentialsInvalidException();
        }

        // Account status is still owned by account-service (S1 physical
        // separation of Identity and Account/Profile).
        AccountStatusLookupResult status = accountServicePort.getAccountStatus(accountId)
                .orElseThrow(() -> {
                    // Credential exists but account is gone — treat as invalid
                    // to avoid leaking "credential-yes, account-no" signal.
                    loginAttemptCounter.incrementFailureCount(emailHash);
                    int newCount = loginAttemptCounter.getFailureCount(emailHash);
                    authEventPublisher.publishLoginFailed(accountId, emailHash,
                            "CREDENTIALS_INVALID", newCount, ctx);
                    return new CredentialsInvalidException();
                });

        checkAccountStatus(status.accountStatus(), accountId, emailHash, ctx);

        // Verify password
        boolean passwordValid = passwordHasher.verify(command.password(), credential.getCredentialHash());
        if (!passwordValid) {
            loginAttemptCounter.incrementFailureCount(emailHash);
            int newCount = loginAttemptCounter.getFailureCount(emailHash);
            authEventPublisher.publishLoginFailed(accountId, emailHash, "CREDENTIALS_INVALID", newCount, ctx);
            throw new CredentialsInvalidException();
        }

        // Login success — register/touch the device_session BEFORE issuing tokens so the
        // device_id is available as a JWT claim and as a refresh_tokens.device_id stamp.
        // Eviction (if needed) runs in the same transaction (D4 atomicity).
        RegisterDeviceSessionResult sessionResult =
                registerOrUpdateDeviceSessionUseCase.execute(accountId, ctx);
        String deviceId = sessionResult.deviceId();

        TokenPair tokenPair = tokenGeneratorPort.generateTokenPair(accountId, "user", deviceId);

        // Extract JTI from refresh token and persist
        String refreshJti = tokenGeneratorPort.extractJti(tokenPair.refreshToken());
        Instant now = Instant.now();
        RefreshToken refreshTokenEntity = RefreshToken.create(
                refreshJti, accountId, now,
                now.plusSeconds(tokenGeneratorPort.refreshTokenTtlSeconds()),
                null,
                // Shadow-write the deprecated device_fingerprint column during the D5
                // migration window. New readers should consult device_id instead.
                ctx.deviceFingerprint(),
                deviceId
        );
        refreshTokenRepository.save(refreshTokenEntity);

        // Reset failure counter
        loginAttemptCounter.resetFailureCount(emailHash);

        // Publish success events: legacy auth.login.succeeded + new auth.session.created
        // (the latter only on a brand-new device_session row, per the spec lifecycle).
        authEventPublisher.publishLoginSucceeded(accountId, refreshJti, ctx,
                deviceId, sessionResult.newSession());
        if (sessionResult.newSession()) {
            authEventPublisher.publishAuthSessionCreated(
                    accountId, deviceId, refreshJti,
                    fingerprintHash(ctx.deviceFingerprint()),
                    ctx.userAgentFamily(),
                    ctx.ipMasked(),
                    ctx.resolvedGeoCountry(),
                    now,
                    sessionResult.evictedDeviceIds());
        }

        return LoginResult.of(tokenPair.accessToken(), tokenPair.refreshToken(), tokenPair.expiresIn());
    }

    /**
     * SHA-256 of the raw fingerprint, hex-encoded. Used for {@code auth.session.created}
     * payload's {@code deviceFingerprintHash} so consumers receive an observation hash
     * instead of the raw fingerprint.
     */
    static String fingerprintHash(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void checkAccountStatus(String status, String accountId, String emailHash, SessionContext ctx) {
        switch (status) {
            case "ACTIVE" -> { /* proceed */ }
            case "LOCKED" -> {
                authEventPublisher.publishLoginFailed(accountId, emailHash, "ACCOUNT_LOCKED", 0, ctx);
                throw new AccountLockedException();
            }
            case "DORMANT" -> {
                authEventPublisher.publishLoginFailed(accountId, emailHash, "ACCOUNT_DORMANT", 0, ctx);
                throw new AccountStatusException("DORMANT", "ACCOUNT_DORMANT");
            }
            case "DELETED" -> {
                authEventPublisher.publishLoginFailed(accountId, emailHash, "ACCOUNT_DELETED", 0, ctx);
                throw new AccountStatusException("DELETED", "ACCOUNT_DELETED");
            }
            default -> {
                authEventPublisher.publishLoginFailed(accountId, emailHash, "ACCOUNT_STATUS_UNKNOWN", 0, ctx);
                throw new AccountStatusException(status, "ACCOUNT_STATUS_UNKNOWN");
            }
        }
    }

    static String hashEmail(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(email.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
