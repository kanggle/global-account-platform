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
     * Mask a phone number: keep country/prefix up to the first 3 digits and
     * the last 2 digits; replace the middle with {@code "***"}. Non-digit
     * separators are preserved.
     *
     * <pre>
     *   "+82-10-1234-5678" -> "+82-10-***-78"
     *   "01012345678"      -> "010***78"
     * </pre>
     */
    public static String maskPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 5) return phone;
        String head = digits.substring(0, 3);
        String tail = digits.substring(digits.length() - 2);
        return head + "***" + tail;
    }
}
