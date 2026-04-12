package com.example.security.query;

/**
 * PII masking utilities for query responses.
 * Used by both SecurityQueryService and consumer/AuthEventMapper.
 */
public final class PiiMaskingUtils {

    private PiiMaskingUtils() {
    }

    /**
     * Mask IP address: replace last octet with '***'.
     * Input may already be masked from the producer; this ensures consistent format.
     */
    public static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        int lastDot = ip.lastIndexOf('.');
        if (lastDot < 0) {
            return ip;
        }
        return ip.substring(0, lastDot + 1) + "***";
    }

    /**
     * Truncate device fingerprint to first 12 characters for PII protection.
     */
    public static String truncateFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() <= 12) {
            return fingerprint;
        }
        return fingerprint.substring(0, 12);
    }
}
