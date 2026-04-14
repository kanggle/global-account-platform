package com.example.admin.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BulkLockRequest(
        @NotNull @NotEmpty List<String> accountIds,
        @NotNull @Size(min = 8, message = "must be at least 8 characters") String reason,
        String ticketId
) {}
