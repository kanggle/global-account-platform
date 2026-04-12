package com.example.auth.domain.session;

/**
 * Value object representing session context information (IP, user agent, device).
 */
public record SessionContext(
        String ipAddress,
        String userAgent,
        String deviceFingerprint
) {
    public String ipMasked() {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return "unknown";
        }
        int lastDot = ipAddress.lastIndexOf('.');
        if (lastDot > 0) {
            return ipAddress.substring(0, lastDot) + ".***";
        }
        return ipAddress;
    }

    public String userAgentFamily() {
        if (userAgent == null || userAgent.isEmpty()) {
            return "unknown";
        }
        if (userAgent.contains("Chrome")) return "Chrome";
        if (userAgent.contains("Firefox")) return "Firefox";
        if (userAgent.contains("Safari")) return "Safari";
        return "Other";
    }
}
