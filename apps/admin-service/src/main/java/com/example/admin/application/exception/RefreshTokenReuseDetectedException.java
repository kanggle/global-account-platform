package com.example.admin.application.exception;

/**
 * TASK-BE-040 — a refresh JWT whose registry row is already revoked has been
 * presented again. Triggers bulk-revocation of the operator's remaining
 * refresh tokens (reason=REUSE_DETECTED) and surfaces as
 * 401 REFRESH_TOKEN_REUSE_DETECTED.
 */
public class RefreshTokenReuseDetectedException extends RuntimeException {
    public RefreshTokenReuseDetectedException(String message) {
        super(message);
    }
}
