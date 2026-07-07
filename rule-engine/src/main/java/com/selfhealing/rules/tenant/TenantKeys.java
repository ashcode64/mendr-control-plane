package com.selfhealing.rules.tenant;

import java.util.UUID;

/**
 * Namespaces Redis keys and pub/sub messages by tenant so cache entries and
 * route-change notifications produced by the rule engine stay isolated per
 * tenant on a shared Redis. Mirrors the api-gateway helper of the same name.
 */
public final class TenantKeys {

    private static final String TENANT_PREFIX = "t:";

    private TenantKeys() {
    }

    /** Prefix {@code key} with the current tenant namespace. */
    public static String scoped(String key) {
        return scoped(TenantContext.currentOrDefault(), key);
    }

    /** Prefix {@code key} with an explicit tenant namespace. */
    public static String scoped(UUID tenantId, String key) {
        return TENANT_PREFIX + tenantId + ":" + key;
    }

    /**
     * Encode a tenant + payload into a single pub/sub message so the gateway
     * subscriber can re-establish the originating tenant.
     * Format: {@code t:{tenantId}|{payload}}.
     */
    public static String encodeMessage(String payload) {
        return TENANT_PREFIX + TenantContext.currentOrDefault() + "|" + payload;
    }
}
