package com.example.account.application.result;

import java.time.Instant;

public record DeleteAccountResult(
        String accountId,
        String status,
        Instant gracePeriodEndsAt,
        String message
) {
}
