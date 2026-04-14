package com.example.auth.domain.session;

import java.util.UUID;

/**
 * Forwarding shim. The canonical implementation now lives in
 * {@link com.example.common.id.UuidV7} (promoted in TASK-BE-028c under the
 * shared-library policy as a pure, multi-service utility).
 *
 * <p>Kept here to avoid churning {@code device-session} callers in this increment.
 * New code should prefer {@code com.example.common.id.UuidV7} directly.
 *
 * @deprecated use {@link com.example.common.id.UuidV7}.
 */
@Deprecated(forRemoval = false)
public final class UuidV7 {

    private UuidV7() {}

    public static UUID randomUuid() {
        return com.example.common.id.UuidV7.randomUuid();
    }

    public static String randomString() {
        return com.example.common.id.UuidV7.randomString();
    }
}
