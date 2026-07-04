package com.selfhealing.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tenant-isolation settings. The application connects to Postgres as the
 * non-superuser {@code app_user} and sets {@code app.current_tenant} per
 * connection so Row-Level Security policies isolate every query.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mendr.tenancy")
public class MultiTenancyProperties {

    /** Well-known default tenant (mirrors infra/init_v2_multitenancy.sql). */
    private UUID defaultTenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * When no tenant context is resolved for a connection, fall back to the
     * default tenant instead of an empty (fail-closed) context. Keeps the
     * platform behaving as single-tenant until every entry point sets a real
     * tenant. Set false for strict isolation once rollout is complete.
     */
    private boolean fallbackToDefault = true;
}
