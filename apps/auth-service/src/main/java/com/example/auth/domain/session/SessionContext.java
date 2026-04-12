package com.example.auth.domain.session;

/**
 * Value object representing session context information (IP, user agent, device, geo).
 */
public record SessionContext(
        String ipAddress,
        String userAgent,
        String deviceFingerprint,
        String geoCountry
) {
    /**
     * Backward-compatible constructor that defaults geoCountry to "XX".
     */
    public SessionContext(String ipAddress, String userAgent, String deviceFingerprint) {
        this(ipAddress, userAgent, deviceFingerprint, "XX");
    }

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

    public String resolvedGeoCountry() {
        return geoCountry != null ? geoCountry : "XX";
    }
}
