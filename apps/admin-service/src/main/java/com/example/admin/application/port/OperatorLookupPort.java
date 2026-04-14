package com.example.admin.application.port;

import java.util.Optional;

/**
 * Resolves an operator's internal BIGINT PK from the external UUID
 * ({@code operator_id}). Used by bulk-lock to key idempotency rows by the
 * internal FK without exposing the admin_operators JPA types to the
 * application layer.
 */
public interface OperatorLookupPort {

    /**
     * @return internal BIGINT id of the operator, or empty when the UUID does
     *         not match any admin_operators row.
     */
    Optional<Long> findInternalId(String operatorId);
}
