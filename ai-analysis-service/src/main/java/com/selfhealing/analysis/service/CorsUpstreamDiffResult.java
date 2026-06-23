package com.selfhealing.analysis.service;

import java.util.LinkedHashMap;
import java.util.Map;

public record CorsUpstreamDiffResult(
        String summary,
        String callerOrigin,
        String outboundOrigin,
        String sourceService,
        String targetService,
        String endpointPath,
        boolean deterministic) {

    public static CorsUpstreamDiffResult empty() {
        return new CorsUpstreamDiffResult("", null, null, null, null, null, false);
    }

    public boolean hasDeterministicRule() {
        return deterministic
                && callerOrigin != null && !callerOrigin.isBlank()
                && outboundOrigin != null && !outboundOrigin.isBlank()
                && endpointPath != null && !endpointPath.isBlank();
    }

    public Map<String, Object> toTransformationRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        if (!hasDeterministicRule()) return rules;
        rules.put("type", "CORS_ORIGIN_OVERRIDE");
        rules.put("sourceService", sourceService);
        rules.put("targetService", targetService);
        rules.put("endpoint", endpointPath);
        rules.put("callerOrigin", callerOrigin);
        rules.put("outboundOrigin", outboundOrigin);
        rules.put("rewriteResponseAcao", true);
        return rules;
    }

    public void appendToPrompt(StringBuilder sb) {
        if (!hasDeterministicRule()) return;
        sb.append("=== STRUCTURED CORS UPSTREAM DIFF (authoritative — follow exactly) ===\n");
        sb.append("Summary: ").append(summary).append("\n");
        sb.append("callerOrigin (real Origin from caller envelope): ").append(callerOrigin).append("\n");
        sb.append("outboundOrigin (Origin Service B accepts): ").append(outboundOrigin).append("\n");
        sb.append("endpointPath: ").append(endpointPath).append("\n");
        sb.append("You MUST propose CORS_ORIGIN_OVERRIDE with these exact callerOrigin, outboundOrigin, and endpointPath.\n");
        sb.append("NEVER use registeredBaseUrl or targetServiceUrl as callerOrigin.\n\n");
    }
}
