package com.selfhealing.gateway.tenant;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * Stamps {@code tenantId} on every {@link TenantScoped} entity before insert
 * or update, but only when the field is still unset. This keeps write-paths in
 * sync with the bound {@link TenantContext} and satisfies the RLS
 * {@code WITH CHECK} policy without overwriting an already-correct tenant.
 */
public class TenantEntityListener {

    @PrePersist
    @PreUpdate
    public void stampTenant(Object entity) {
        if (entity instanceof TenantScoped scoped && scoped.getTenantId() == null) {
            scoped.setTenantId(TenantContext.currentOrDefault());
        }
    }
}
