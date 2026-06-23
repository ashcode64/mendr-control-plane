package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;

import java.util.List;

/**
 * Assembled facts for a single failure analysis run.
 */
public record FailureAnalysisContext(
        ApiFailureEvent event,
        String category,
        ContractContext contracts,
        RegistryDiscoveryContext registry,
        CorsPolicyContext corsPolicy,
        List<String> upstreamAllowedOrigins,
        List<String> mendrEdgeAllowedOrigins,
        List<ActiveRuleSummary> activeRulesOnRoute,
        SchemaDiffResult schemaDiff,
        ResponseDiffResult responseDiff,
        CorsUpstreamDiffResult corsUpstreamDiff,
        CorsEdgeDiffResult corsEdgeDiff) {

    public static FailureAnalysisContext minimal(ApiFailureEvent event, String category) {
        return new FailureAnalysisContext(
                event, category,
                new ContractContext(null, null, null, null),
                new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of()),
                CorsPolicyContext.empty(),
                List.of(), List.of(), List.of(),
                SchemaDiffResult.empty(),
                ResponseDiffResult.empty(),
                CorsUpstreamDiffResult.empty(),
                CorsEdgeDiffResult.empty());
    }
}
