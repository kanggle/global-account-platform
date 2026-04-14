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

    private final int velocityThreshold;       // failures in window before VelocityRule fires
    private final int velocityWindowSeconds;   // rolling window length
    private final int velocityScoreWeight;     // score multiplier at threshold (spec UC-10: 80)
    private final int geoSpeedKmPerHour;       // physically-impossible travel speed (default 900)
    private final int geoMinScore;             // floor for GeoAnomalyRule when it fires
    private final int geoScoreSlope;           // score increment per 1.0x over speed threshold
    private final int deviceChangeScore;       // fixed score for DeviceChangeRule
    private final boolean deviceAlertOnNew;    // emit alert on newly-seen device

    public DetectionThresholds(int velocityThreshold,
                               int velocityWindowSeconds,
                               int velocityScoreWeight,
                               int geoSpeedKmPerHour,
                               int geoMinScore,
                               int geoScoreSlope,
                               int deviceChangeScore,
                               boolean deviceAlertOnNew) {
        if (velocityThreshold <= 0) {
            throw new IllegalArgumentException("velocityThreshold must be > 0");
        }
        if (velocityWindowSeconds <= 0) {
            throw new IllegalArgumentException("velocityWindowSeconds must be > 0");
        }
        if (velocityScoreWeight <= 0 || velocityScoreWeight > 100) {
            throw new IllegalArgumentException("velocityScoreWeight must be in (0,100]");
        }
        if (geoSpeedKmPerHour <= 0) {
            throw new IllegalArgumentException("geoSpeedKmPerHour must be > 0");
        }
        if (geoMinScore < 0 || geoMinScore > 100) {
            throw new IllegalArgumentException("geoMinScore must be in [0,100]");
        }
        if (geoScoreSlope < 0 || geoScoreSlope > 100) {
            throw new IllegalArgumentException("geoScoreSlope must be in [0,100]");
        }
        if (deviceChangeScore < 0 || deviceChangeScore > 100) {
            throw new IllegalArgumentException("deviceChangeScore must be in [0,100]");
        }
        this.velocityThreshold = velocityThreshold;
        this.velocityWindowSeconds = velocityWindowSeconds;
        this.velocityScoreWeight = velocityScoreWeight;
        this.geoSpeedKmPerHour = geoSpeedKmPerHour;
        this.geoMinScore = geoMinScore;
        this.geoScoreSlope = geoScoreSlope;
        this.deviceChangeScore = deviceChangeScore;
        this.deviceAlertOnNew = deviceAlertOnNew;
    }

    public int velocityThreshold() { return velocityThreshold; }
    public int velocityWindowSeconds() { return velocityWindowSeconds; }
    public int velocityScoreWeight() { return velocityScoreWeight; }
    public int geoSpeedKmPerHour() { return geoSpeedKmPerHour; }
    public int geoMinScore() { return geoMinScore; }
    public int geoScoreSlope() { return geoScoreSlope; }
    public int deviceChangeScore() { return deviceChangeScore; }
    public boolean deviceAlertOnNew() { return deviceAlertOnNew; }

    public static DetectionThresholds defaults() {
        return new DetectionThresholds(10, 3600, 80, 900, 85, 15, 50, true);
    }
}
