package com.example.security.infrastructure.config;

import com.example.security.domain.detection.DetectionThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration for detection rules.
 * Properties live under {@code security.detection.*} in {@code application.yml}.
 * No hard-coded thresholds in rule code per spec.
 */
@ConfigurationProperties(prefix = "security.detection")
public class DetectionProperties {

    private Velocity velocity = new Velocity();
    private Geo geo = new Geo();
    private Device device = new Device();
    private AutoLock autoLock = new AutoLock();
    private GeoIp geoip = new GeoIp();

    public DetectionThresholds toThresholds() {
        return new DetectionThresholds(
                velocity.threshold,
                velocity.windowSeconds,
                geo.speedKmPerHour,
                geo.minScore,
                device.score
        );
    }

    public Velocity getVelocity() { return velocity; }
    public void setVelocity(Velocity velocity) { this.velocity = velocity; }
    public Geo getGeo() { return geo; }
    public void setGeo(Geo geo) { this.geo = geo; }
    public Device getDevice() { return device; }
    public void setDevice(Device device) { this.device = device; }
    public AutoLock getAutoLock() { return autoLock; }
    public void setAutoLock(AutoLock autoLock) { this.autoLock = autoLock; }
    public GeoIp getGeoip() { return geoip; }
    public void setGeoip(GeoIp geoip) { this.geoip = geoip; }

    public static class Velocity {
        private int threshold = 10;
        private int windowSeconds = 3600;
        public int getThreshold() { return threshold; }
        public void setThreshold(int threshold) { this.threshold = threshold; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    }

    public static class Geo {
        private int speedKmPerHour = 900;
        private int minScore = 85;
        public int getSpeedKmPerHour() { return speedKmPerHour; }
        public void setSpeedKmPerHour(int speedKmPerHour) { this.speedKmPerHour = speedKmPerHour; }
        public int getMinScore() { return minScore; }
        public void setMinScore(int minScore) { this.minScore = minScore; }
    }

    public static class Device {
        private int score = 50;
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
    }

    public static class AutoLock {
        private String accountServiceBaseUrl = "http://localhost:8081";
        private int maxAttempts = 3;
        private long initialBackoffMs = 200L;
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 10000;
        public String getAccountServiceBaseUrl() { return accountServiceBaseUrl; }
        public void setAccountServiceBaseUrl(String accountServiceBaseUrl) { this.accountServiceBaseUrl = accountServiceBaseUrl; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    public static class GeoIp {
        private String dbPath = "";
        public String getDbPath() { return dbPath; }
        public void setDbPath(String dbPath) { this.dbPath = dbPath; }
    }
}
