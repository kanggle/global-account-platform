package com.example.account.presentation.advice;

import com.example.account.application.exception.AccountAlreadyExistsException;
import com.example.account.application.exception.AccountNotFoundException;
import com.example.account.application.exception.EmailAlreadyVerifiedException;
import com.example.account.application.exception.EmailVerificationTokenInvalidException;
import com.example.account.application.exception.RateLimitedException;
import com.example.account.application.port.AuthServicePort;
import com.example.account.domain.status.StateTransitionException;
import com.example.web.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleAccountAlreadyExists(AccountAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("ACCOUNT_ALREADY_EXISTS", "An account with this email already exists"));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    @ExceptionHandler(StateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleStateTransitionInvalid(StateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("STATE_TRANSITION_INVALID", e.getMessage()));
    }

    /**
     * TASK-BE-114: email-verify token is missing, expired, or already consumed.
     * All three conditions surface uniformly so the API does not leak which
     * one tripped (mirrors auth-service's password-reset confirm path).
     */
    @ExceptionHandler(EmailVerificationTokenInvalidException.class)
    public ResponseEntity<ErrorResponse> handleEmailVerificationTokenInvalid(
            EmailVerificationTokenInvalidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("TOKEN_EXPIRED_OR_INVALID",
                        "Email verification token is invalid or has expired"));
    }

    /**
     * TASK-BE-114: verify-email or resend on an already-verified account.
     */
    @ExceptionHandler(EmailAlreadyVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyVerified(EmailAlreadyVerifiedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("EMAIL_ALREADY_VERIFIED",
                        "Email is already verified"));
    }

    /**
     * TASK-BE-114: 5-minute resend-verification-email rate limit hit.
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of("RATE_LIMITED", e.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONFLICT", "Concurrent modification detected. Please retry."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedRequest(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", "Malformed request body"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", "Missing required header: " + e.getHeaderName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", "Missing required parameter: " + e.getParameterName()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", e.getMessage()));
    }

    // TASK-BE-065: auth-service 5xx / timeout / circuit-open 시 signup 은 503 fail-closed
    // (specs/contracts/http/internal/auth-internal.md §Failure Scenarios).
    @ExceptionHandler(AuthServicePort.AuthServiceUnavailable.class)
    public ResponseEntity<ErrorResponse> handleAuthServiceUnavailable(AuthServicePort.AuthServiceUnavailable e) {
        log.error("auth-service unavailable during signup: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("AUTH_SERVICE_UNAVAILABLE",
                        "Authentication service is temporarily unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
