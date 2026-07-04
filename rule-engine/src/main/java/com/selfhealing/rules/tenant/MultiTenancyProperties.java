package com.selfhealing.rules.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tenant-isolation settings. The service connects to Postgres as the
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
     * default tenant instead of an empty (fail-closed) context.
     */
    private boolean fallbackToDefault = true;
}
