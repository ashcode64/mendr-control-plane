package com.selfhealing.analysis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Security settings for the ai-analysis HTTP surface (the MCP endpoint and the
 * analysis controller). The MCP + analysis endpoints are reached machine-to-machine
 * by the conversation engine and the gateway using the shared internal API key; the
 * dashboard reaches {@code /api/analysis} through the frontend proxy.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mendr.analysis.security")
public class AnalysisSecurityProperties {

    /**
     * When true, the machine-only {@code /mcp} endpoint requires the shared internal
     * API key. When false (default) it stays open for the incremental rollout, but the
     * tenant is still bound from a trusted {@code X-Tenant-Id}. Dashboard-facing
     * {@code /api/analysis} calls are authenticated with the WorkOS JWT, not this key.
     */
    private boolean enforce = false;

    /** Shared internal key (same value as the gateway's GATEWAY_INTERNAL_API_KEY). */
    private String internalApiKey = "";

    /** Allowed browser origins for {@code /api/analysis} (never {@code *}). */
    private List<String> corsAllowedOrigins = List.of("http://localhost:3000");

    /** Human (dashboard) auth: WorkOS JWT validated via JWKS. */
    private final Workos workos = new Workos();

    @Data
    public static class Workos {
        /** JWKS endpoint of the WorkOS environment. Empty disables JWT validation. */
        private String jwksUri = "";
        private String issuer = "";
        private String audience = "";
        /** JWT claim that carries the WorkOS organization id. */
        private String orgClaim = "org_id";
    }
}
