package com.example.admin.application.util;

/**
 * Central PII masking utility for admin-service outbox envelopes and operator
 * display surfaces. Pattern parallels {@code security-service}'s
 * {@code PiiMaskingUtils} — kept inside the application layer (no framework
 * dependency) so both the event publisher and any future query-layer mapper
 * can depend on it without violating layer dependency rules.
 *
 * <p>All methods return {@code null} for {@code null} input and leave obviously
 * non-PII values (e.g. UUID-looking strings) unchanged.
 */
public final class AdminPiiMaskingUtils {

    private AdminPiiMaskingUtils() {}

    /**
     * Mask the local-part of an email: keep first char + domain, replace the
     * rest of the local-part with {@code "***"}. Values without an {@code @}
     * are returned unchanged.
     *
     * <pre>
     *   "jane.doe@example.com" -> "j***@example.com"
     *   "a@example.com"        -> "a***@example.com"
     *   "no-at-sign"           -> "no-at-sign"
     * </pre>
     */
    public static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * Mask an account identifier. The canonical account-id is a UUID and is
     * not itself PII, but when admins pass free-form identifiers that happen
     * to embed an email, mask the embedded email. Otherwise return unchanged.
     */
    public static String maskAccountId(String accountId) {
        if (accountId == null) return null;
        if (accountId.contains("@")) return maskEmail(accountId);
        return accountId;
    }

    /**
     * Mask a phone number per {@code rules/traits/regulated.md} R4 canonical
     * format {@code "010-****-1234"}: strip separators, keep the first 3
     * digits, replace the middle with {@code "****"}, and keep the last 4
     * digits. Numbers with 7 or fewer digits are returned unchanged (consistent
     * with the existing short-number guard).
     *
     * <pre>
     *   "01012345678"      -> "010-****-5678"
     *   "+82-10-1234-5678" -> "821-****-5678"
     * </pre>
     */
    public static String maskPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 7) return phone;
        String head = digits.substring(0, 3);
        String tail = digits.substring(digits.length() - 4);
        return head + "-****-" + tail;
    }
}
