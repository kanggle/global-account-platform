package com.example.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Minimal UUID v7 (time-ordered, RFC 9562) generator.
 *
 * <p>Promoted to {@code libs/java-common} per TASK-BE-028c. A pure utility with no
 * service-owned domain logic; meets shared-library policy criteria (technical,
 * multi-service, stable). Used for:
 * <ul>
 *   <li>{@code auth-service} {@code device_sessions.device_id}
 *       (specs/services/auth-service/device-session.md D1)</li>
 *   <li>{@code admin-service} {@code admin_operators.operator_id} and the
 *       {@code admin.action.performed} outbox envelope {@code eventId}
 *       (specs/services/admin-service/rbac.md D4, data-model.md)</li>
 * </ul>
 *
 * <p>Layout (128 bits):
 * <pre>
 *   48 bits unix_ts_ms | 4 bits version (0111) | 12 bits rand_a |
 *   2 bits variant (10) | 62 bits rand_b
 * </pre>
 *
 * <p>Clock regression across JVM invocations may break monotonicity — RFC 9562
 * explicitly permits this (task TASK-BE-028c Edge Cases).
 */
public final class UuidV7 {

    private static final SecureRandom RNG = new SecureRandom();

    private UuidV7() {}

    /** Generate a new time-ordered UUID v7. */
    public static UUID randomUuid() {
        long timestampMs = System.currentTimeMillis();
        byte[] randomBytes = new byte[10];
        RNG.nextBytes(randomBytes);

        long msb = (timestampMs & 0xFFFFFFFFFFFFL) << 16;
        // version = 7 in the high nibble of byte 6
        msb |= (0x7L << 12);
        // 12 bits rand_a
        msb |= ((randomBytes[0] & 0x0FL) << 8) | (randomBytes[1] & 0xFFL);

        long lsb = 0L;
        // variant bits = 10
        lsb |= (0x2L << 62);
        // 62 bits rand_b from randomBytes[2..9]; mask top 2 bits of first byte
        lsb |= ((long) (randomBytes[2] & 0x3F) << 56);
        for (int i = 3; i < 10; i++) {
            lsb |= ((long) (randomBytes[i] & 0xFF)) << (8 * (9 - i));
        }
        return new UUID(msb, lsb);
    }

    /** Generate a new UUID v7 and return its canonical string form. */
    public static String randomString() {
        return randomUuid().toString();
    }

    /** Extract the 48-bit unix millisecond timestamp embedded in a UUID v7. */
    public static long timestampMs(UUID uuid) {
        return (uuid.getMostSignificantBits() >>> 16) & 0xFFFFFFFFFFFFL;
    }
}
