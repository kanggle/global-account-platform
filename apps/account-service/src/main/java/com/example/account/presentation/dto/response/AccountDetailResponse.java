package com.example.account.presentation.dto.response;

import java.time.Instant;

public record AccountDetailResponse(
        String id,
        String email,
        String status,
        Instant createdAt,
        Profile profile
) {
    public record Profile(
            String displayName,
            String phoneMasked
    ) {}

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return null;
        return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }

    public static AccountDetailResponse of(
            com.example.account.infrastructure.persistence.AccountJpaEntity account,
            com.example.account.infrastructure.persistence.ProfileJpaEntity profile) {
        Profile p = profile == null ? null
                : new Profile(profile.getDisplayName(), maskPhone(profile.getPhoneNumber()));
        return new AccountDetailResponse(
                account.getId(), account.getEmail(), account.getStatus().name(),
                account.getCreatedAt(), p);
    }
}
