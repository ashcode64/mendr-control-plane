package com.selfhealing.gateway.tenant;

import java.util.UUID;

/**
 * Namespaces Redis keys and pub/sub messages by tenant so cache entries,
 * dedup markers, route snapshots and sync state of one tenant can never be
 * read, overwritten or invalidated by another.
 *
 * <p>Keys are prefixed {@code t:{tenantId}:} using the bound
 * {@link TenantContext} (falling back to the default tenant), so the physical
 * Redis keyspace is partitioned per tenant on a shared Redis. The control-plane
 * Redis is internal (each edge gateway has its own Redis and syncs over HTTP),
 * so changing the physical key layout is safe.
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

    /** The bare namespace prefix for the current tenant (e.g. for SCAN/pattern ops). */
    public static String prefix() {
        return TENANT_PREFIX + TenantContext.currentOrDefault() + ":";
    }

    /**
     * Encode a tenant + payload into a single pub/sub message so a subscriber
     * with no thread-bound context can re-establish the originating tenant.
     * Format: {@code t:{tenantId}|{payload}}.
     */
    public static String encodeMessage(String payload) {
        return TENANT_PREFIX + TenantContext.currentOrDefault() + "|" + payload;
    }

    /** Decoded {@link #encodeMessage(String)} message: tenant + original payload. */
    public record TenantMessage(UUID tenantId, String payload) {
    }

    /**
     * Decode a message produced by {@link #encodeMessage(String)}. Messages that
     * are not tenant-encoded (legacy / external) decode to the default tenant
     * with the raw message as payload, so behaviour degrades safely.
     */
    public static TenantMessage decodeMessage(String message) {
        if (message != null && message.startsWith(TENANT_PREFIX)) {
            int bar = message.indexOf('|');
            if (bar > TENANT_PREFIX.length()) {
                String idPart = message.substring(TENANT_PREFIX.length(), bar);
                try {
                    return new TenantMessage(UUID.fromString(idPart), message.substring(bar + 1));
                } catch (IllegalArgumentException ignored) {
                    // fall through to default-tenant decoding
                }
            }
        }
        return new TenantMessage(TenantContext.DEFAULT_TENANT_ID, message);
    }
}
