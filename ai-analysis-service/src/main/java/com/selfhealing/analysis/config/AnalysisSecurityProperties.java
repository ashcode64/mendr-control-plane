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
     * When true, {@code /mcp} and mutating {@code /api/analysis} calls require the
     * shared internal API key. When false (default) the endpoints stay open for the
     * incremental rollout, but the tenant is still bound from {@code X-Tenant-Id}.
     */
    private boolean enforce = false;

    /** Shared internal key (same value as the gateway's GATEWAY_INTERNAL_API_KEY). */
    private String internalApiKey = "";

    /** Allowed browser origins for {@code /api/analysis} (never {@code *}). */
    private List<String> corsAllowedOrigins = List.of("http://localhost:3000");
}
