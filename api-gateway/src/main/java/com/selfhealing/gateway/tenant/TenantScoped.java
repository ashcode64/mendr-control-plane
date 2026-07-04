package com.selfhealing.gateway.tenant;

import java.util.UUID;

/**
 * Marks a JPA entity as belonging to a tenant. {@link TenantEntityListener}
 * stamps {@code tenantId} from the current {@link TenantContext} on insert when
 * it is not already set, so writes land in the caller's tenant (and satisfy the
 * Postgres RLS {@code WITH CHECK} policy) instead of relying on a column default.
 */
public interface TenantScoped {

    UUID getTenantId();

    void setTenantId(UUID tenantId);
}
