package com.selfhealing.analysis.service;

import java.util.List;

/**
 * Deterministic Case 1: Mendr edge blocked the caller Origin.
 */
public final class CorsEdgeAnalyzer {

    private CorsEdgeAnalyzer() {}

    public static CorsEdgeDiffResult analyze(
            String requestOrigin,
            String targetService,
            List<String> mendrEdgeAllowedOrigins,
            String registeredBaseUrl,
            String targetServiceUrl) {

        if (requestOrigin == null || requestOrigin.isBlank()) {
            return CorsEdgeDiffResult.empty();
        }

        String origin = requestOrigin.trim();
        if (RuleValidator.looksLikeServiceBaseUrl(origin, registeredBaseUrl, targetServiceUrl)) {
            return CorsEdgeDiffResult.empty();
        }

        if (mendrEdgeAllowedOrigins != null && mendrEdgeAllowedOrigins.contains(origin)) {
            return CorsEdgeDiffResult.empty();
        }

        String summary = String.format(
                "Mendr edge blocked Origin '%s' for target '%s'; allow at Mendr edge via CORS_ALLOW.",
                origin, targetService);

        return new CorsEdgeDiffResult(summary, origin, targetService, true);
    }
}
