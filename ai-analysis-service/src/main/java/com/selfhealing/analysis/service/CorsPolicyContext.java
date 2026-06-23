package com.selfhealing.analysis.service;

import java.util.List;
import java.util.Map;

/**
 * Parsed cors-policy service contract (version cors-policy).
 */
public record CorsPolicyContext(
        String allowedCallerOrigin,
        String blockedCallerOrigin,
        String targetService) {

    public static CorsPolicyContext empty() {
        return new CorsPolicyContext(null, null, null);
    }

    @SuppressWarnings("unchecked")
    public static CorsPolicyContext fromContract(Object contract) {
        if (!(contract instanceof Map<?, ?> map) || map.isEmpty()) {
            return empty();
        }
        return new CorsPolicyContext(
                str(map.get("allowedCallerOrigin")),
                str(map.get("blockedCallerOrigin")),
                str(map.get("targetService")));
    }

    public List<String> upstreamAllowlist() {
        if (allowedCallerOrigin != null && !allowedCallerOrigin.isBlank()) {
            return List.of(allowedCallerOrigin.trim());
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }
}
