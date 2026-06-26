package com.selfhealing.rules.tenant;

import java.util.UUID;

/**
 * Holds the resolved tenant for the current thread (Kafka listener). Read by
 * {@code TenantAwareDataSource} when a JDBC connection is borrowed so the
 * matching {@code app.current_tenant} is set for Row-Level Security.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID getTenantId() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
