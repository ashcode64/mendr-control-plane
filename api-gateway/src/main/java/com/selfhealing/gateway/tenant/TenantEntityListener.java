package com.selfhealing.gateway.tenant;

import jakarta.persistence.PrePersist;

/**
 * Default JPA entity listener (registered in {@code META-INF/orm.xml}) that
 * stamps {@code tenantId} on every {@link TenantScoped} entity at insert time
 * from the current {@link TenantContext}, unless it is already set. This keeps
 * writes inside the caller's tenant and satisfies the RLS {@code WITH CHECK}
 * policy instead of depending on the column default.
 */
public class TenantEntityListener {

    @PrePersist
    public void stampTenant(Object entity) {
        if (entity instanceof TenantScoped scoped && scoped.getTenantId() == null) {
            scoped.setTenantId(TenantContext.currentOrDefault());
        }
    }
}
