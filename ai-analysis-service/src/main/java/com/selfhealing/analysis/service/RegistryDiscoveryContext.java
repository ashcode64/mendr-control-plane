package com.selfhealing.analysis.service;

import java.util.List;
import java.util.Map;

/**
 * Service registry, routing overrides, and DNS probe results for AI prompt enrichment.
 */
public record RegistryDiscoveryContext(
        List<Map<String, Object>> involvedServices,
        List<Map<String, Object>> activeRoutingRules,
        List<Map<String, Object>> recentDnsProbes,
        List<Map<String, Object>> allActiveServices
) {
    boolean hasAny() {
        return !involvedServices.isEmpty()
                || !activeRoutingRules.isEmpty()
                || !recentDnsProbes.isEmpty();
    }
}
