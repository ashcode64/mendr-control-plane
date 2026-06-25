package com.selfhealing.analysis.service.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * CORS-relevant facts. Present for CORS (Mendr edge) and CORS_UPSTREAM (Service B).
 * Structural {@code corsBlockedAt} replaces inferring EDGE vs UPSTREAM from prose.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorsContext(
        String requestOrigin,
        String corsBlockedAt,
        List<String> mendrEdgeAllowedOrigins,
        List<String> upstreamAllowedOrigins,
        String upstreamOriginSent,
        String policyAllowedCallerOrigin,
        Object actualRequestPayload,
        Object actualResponsePayload
) {
}
