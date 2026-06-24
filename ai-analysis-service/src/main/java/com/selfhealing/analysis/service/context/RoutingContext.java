package com.selfhealing.analysis.service.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Routing-relevant facts. Present for ROUTING and (for "it is NOT this" checks)
 * CORS_UPSTREAM. {@code registeredBaseUrl}/{@code targetServiceUrl} are routing
 * URLs, never Origins.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoutingContext(
        String sourceService,
        String targetService,
        String endpoint,
        String registeredBaseUrl,
        String targetServiceUrl,
        String attemptedUrl,
        String dnsProbeDiscoveryUrl,
        List<DnsProbeSummary> recentProbes,
        List<RegisteredService> involvedServices,
        List<RegisteredService> allActiveServices,
        List<RoutingOverrideSummary> activeRoutingOverrides
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DnsProbeSummary(String probedUrl, boolean reachable, Object httpStatus) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisteredService(String name, String baseUrl, Object healthStatus, String namespace) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoutingOverrideSummary(String serviceName, String originalUrl, String newUrl, String discoveryMethod) {}
}
