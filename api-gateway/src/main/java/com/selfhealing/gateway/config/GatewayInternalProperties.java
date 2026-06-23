package com.selfhealing.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gateway.internal")
public class GatewayInternalProperties {

    /** Optional shared secret for /api/internal/* (OpenResty data plane). Empty = disabled in dev. */
    private String apiKey = "";

    /** Java-side dedup TTL for validate-response republication suppression (seconds). */
    private int validateDedupTtlSeconds = 60;

    /** Java-side dedup TTL for /api/internal/failures (seconds). */
    private int failureDedupTtlSeconds = 60;

    /** Redis TTL for mendr:routeconfig:* snapshots (seconds). 0 = no expiry. */
    private int routeConfigSnapshotTtlSeconds = 0;

    /**
     * Per-route endpoints that require synchronous response contract validation.
     * Format: "sourceService:targetService:endpoint" or "*:targetService:endpoint".
     * When matched, Lua sets syncValidation=true in the route snapshot, skipping
     * async validation; the Java proxy path handles it synchronously instead.
     */
    private java.util.List<String> syncValidationEndpoints = new java.util.ArrayList<>();
}
