package com.example.account.domain.repository;

import com.example.account.domain.account.Account;
import com.example.account.domain.tenant.TenantId;

import java.util.Optional;

/**
 * Port interface for account persistence.
 *
 * <p>All query methods require a {@link TenantId} as the first argument to enforce
 * row-level isolation at the call site. There are no single-argument lookup methods
 * that could cause cross-tenant data leaks.
 *
 * <p>Rule (specs/features/multi-tenancy.md § Repository level):
 * "All JPA repository methods must receive tenant_id as the first argument.
 * findById(id) without tenant_id is forbidden."
 */
public interface AccountRepository {

    /**
     * Persist or update an account. The account must carry a non-null {@link TenantId}.
     */
    Account save(Account account);

    /**
     * Tenant-scoped lookup by account id. Returns empty when the id exists in a
     * different tenant — cross-tenant ids are never visible across tenant boundaries.
     */
    Optional<Account> findById(TenantId tenantId, String id);

    /**
     * Tenant-scoped lookup by email address.
     */
    Optional<Account> findByEmail(TenantId tenantId, String email);

    /**
     * Tenant-scoped existence check by email address.
     */
    boolean existsByEmail(TenantId tenantId, String email);
}
