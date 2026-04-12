package com.example.admin.application.exception;

/**
 * Thrown when a downstream call returns a 4xx response. These are caller errors
 * and must not be retried — Resilience4j {@code @Retry} instances are configured
 * with this class in {@code ignoreExceptions}.
 */
public class NonRetryableDownstreamException extends DownstreamFailureException {
    public NonRetryableDownstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
