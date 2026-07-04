package com.selfhealing.gateway.tenant;

import java.util.UUID;

/**
 * Holds the resolved tenant for the current request thread. Read by
 * {@link TenantAwareDataSource} when a JDBC connection is borrowed, so the
 * matching {@code app.current_tenant} is set for Row-Level Security.
 */
public final class TenantContext {

    /** Well-known default tenant (mirrors infra/init_v2_multitenancy.sql). */
    public static final UUID DEFAULT_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID getTenantId() {
        return CURRENT.get();
    }

    /**
     * The bound tenant, or the default tenant when none is set. Used by write
     * paths to stamp {@code tenant_id} so it always matches the connection's
     * {@code app.current_tenant} (which also falls back to default).
     */
    public static UUID currentOrDefault() {
        UUID current = CURRENT.get();
        return current != null ? current : DEFAULT_TENANT_ID;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
