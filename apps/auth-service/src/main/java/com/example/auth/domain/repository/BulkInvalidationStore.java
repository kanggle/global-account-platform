package com.example.auth.domain.repository;

/**
 * Port for the bulk session invalidation marker.
 *
 * <p>When a critical security event (token reuse, admin-triggered logout-all, etc.) requires
 * all refresh tokens for a given account to be treated as invalid, implementations set a
 * per-account marker (e.g. {@code refresh:invalidate-all:{accountId}}) with a TTL equal to the
 * maximum refresh-token lifetime. Refresh token rotation paths must consult this marker and
 * reject any token whose {@code iat} precedes it.
 */
public interface BulkInvalidationStore {

    /**
     * Marks all current refresh tokens for the given account as invalid for {@code ttlSeconds}.
     * Idempotent — calling again refreshes the TTL.
     */
    void invalidateAll(String accountId, long ttlSeconds);

    /**
     * Returns {@code true} if a bulk-invalidation marker currently exists for the account.
     *
     * <p>Implementations should fail-closed: if the store is unavailable, treat as present.
     */
    boolean isInvalidated(String accountId);
}
