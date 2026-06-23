package com.selfhealing.analysis.service;

import java.util.LinkedHashMap;
import java.util.Map;

public record CorsEdgeDiffResult(
        String summary,
        String blockedOrigin,
        String targetService,
        boolean deterministic) {

    public static CorsEdgeDiffResult empty() {
        return new CorsEdgeDiffResult("", null, null, false);
    }

    public boolean hasDeterministicRule() {
        return deterministic && blockedOrigin != null && !blockedOrigin.isBlank();
    }

    public Map<String, Object> toTransformationRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        if (!hasDeterministicRule()) return rules;
        rules.put("type", "CORS_ALLOW");
        rules.put("targetService", targetService);
        rules.put("newOrigin", blockedOrigin);
        rules.put("previousOrigin", null);
        rules.put("allowedMethods", "GET,POST,PUT,DELETE,PATCH,OPTIONS");
        rules.put("allowedHeaders", "*");
        return rules;
    }

    public void appendToPrompt(StringBuilder sb) {
        if (!hasDeterministicRule()) return;
        sb.append("=== STRUCTURED CORS EDGE DIFF (authoritative) ===\n");
        sb.append("Summary: ").append(summary).append("\n");
        sb.append("blockedOrigin (must become newOrigin in CORS_ALLOW): ").append(blockedOrigin).append("\n");
        sb.append("Do NOT use registeredBaseUrl as newOrigin.\n\n");
    }
}
