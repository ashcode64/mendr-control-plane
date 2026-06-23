package com.selfhealing.analysis.service;

import java.util.List;

/**
 * Deterministic Case 2 analysis: upstream service B rejected the caller's Origin.
 */
public final class CorsUpstreamAnalyzer {

    private CorsUpstreamAnalyzer() {}

    public static CorsUpstreamDiffResult analyze(
            String requestOrigin,
            String sourceService,
            String targetService,
            String endpointPath,
            CorsPolicyContext corsPolicy,
            List<String> upstreamAllowedOrigins,
            String registeredBaseUrl,
            String targetServiceUrl) {

        if (requestOrigin == null || requestOrigin.isBlank()) {
            return CorsUpstreamDiffResult.empty();
        }

        String caller = requestOrigin.trim();
        if (RuleValidator.looksLikeServiceBaseUrl(caller, registeredBaseUrl, targetServiceUrl)) {
            return CorsUpstreamDiffResult.empty();
        }

        List<String> allowlist = upstreamAllowedOrigins != null && !upstreamAllowedOrigins.isEmpty()
                ? upstreamAllowedOrigins
                : corsPolicy.upstreamAllowlist();

        String outbound = pickOutboundOrigin(caller, allowlist, registeredBaseUrl, targetServiceUrl);
        if (outbound == null) {
            return CorsUpstreamDiffResult.empty();
        }

        String path = EndpointNormalizer.normalize(endpointPath);
        String summary = String.format(
                "Service B rejected caller Origin '%s'; rewrite outbound Origin to '%s' (in upstream allowlist).",
                caller, outbound);

        return new CorsUpstreamDiffResult(
                summary, caller, outbound, sourceService, targetService, path, true);
    }

    private static String pickOutboundOrigin(
            String caller,
            List<String> allowlist,
            String registeredBaseUrl,
            String targetServiceUrl) {

        for (String candidate : allowlist) {
            if (candidate == null || candidate.isBlank()) continue;
            String c = candidate.trim();
            if (c.equalsIgnoreCase(caller)) continue;
            if (RuleValidator.looksLikeServiceBaseUrl(c, registeredBaseUrl, targetServiceUrl)) continue;
            return c;
        }
        return null;
    }
}
