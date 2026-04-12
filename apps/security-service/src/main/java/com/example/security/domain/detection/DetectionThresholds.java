package com.example.security.domain.detection;

/**
 * Tunable detection parameters. Injected via Spring
 * {@code @ConfigurationProperties} in the infrastructure layer but the type
 * itself is domain-pure (no framework imports) so rule implementations can
 * depend on it directly.
 *
 * <p>See {@code application.yml} property {@code security.detection.*}.</p>
 */
public final class DetectionThresholds {

    private final int velocityThreshold;      // failures in window before VelocityRule fires
    private final int velocityWindowSeconds;  // rolling window length
    private final int geoSpeedKmPerHour;      // physically-impossible travel speed (default 900)
    private final int geoMinScore;            // floor for GeoAnomalyRule when it fires
    private final int deviceChangeScore;      // fixed score for DeviceChangeRule

    public DetectionThresholds(int velocityThreshold,
                               int velocityWindowSeconds,
                               int geoSpeedKmPerHour,
                               int geoMinScore,
                               int deviceChangeScore) {
        if (velocityThreshold <= 0) {
            throw new IllegalArgumentException("velocityThreshold must be > 0");
        }
        if (velocityWindowSeconds <= 0) {
            throw new IllegalArgumentException("velocityWindowSeconds must be > 0");
        }
        if (geoSpeedKmPerHour <= 0) {
            throw new IllegalArgumentException("geoSpeedKmPerHour must be > 0");
        }
        if (geoMinScore < 0 || geoMinScore > 100) {
            throw new IllegalArgumentException("geoMinScore must be in [0,100]");
        }
        if (deviceChangeScore < 0 || deviceChangeScore > 100) {
            throw new IllegalArgumentException("deviceChangeScore must be in [0,100]");
        }
        this.velocityThreshold = velocityThreshold;
        this.velocityWindowSeconds = velocityWindowSeconds;
        this.geoSpeedKmPerHour = geoSpeedKmPerHour;
        this.geoMinScore = geoMinScore;
        this.deviceChangeScore = deviceChangeScore;
    }

    public int velocityThreshold() { return velocityThreshold; }
    public int velocityWindowSeconds() { return velocityWindowSeconds; }
    public int geoSpeedKmPerHour() { return geoSpeedKmPerHour; }
    public int geoMinScore() { return geoMinScore; }
    public int deviceChangeScore() { return deviceChangeScore; }

    public static DetectionThresholds defaults() {
        return new DetectionThresholds(10, 3600, 900, 85, 50);
    }
}
