package com.example.account.presentation.dto.response;

import com.example.account.application.result.DeleteAccountResult;

import java.time.Instant;

public record DeleteAccountResponse(
        String accountId,
        String status,
        Instant gracePeriodEndsAt,
        String message
) {
    public static DeleteAccountResponse from(DeleteAccountResult result) {
        return new DeleteAccountResponse(
                result.accountId(),
                result.status(),
                result.gracePeriodEndsAt(),
                result.message()
        );
    }
}
