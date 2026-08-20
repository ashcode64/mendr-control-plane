package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Failure report from OpenResty log.lua (data plane). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestFailureRequest {

    private String sourceService;
    private String targetService;
    private String endpoint;
    private String httpMethod;

    private int errorCode;
    private String errorType;
    private String failureCategory;
    private String errorMessage;

    private Map<String, Object> requestPayload;
    private Map<String, Object> responsePayload;

    private String attemptedUrl;
    private String requestOrigin;
    private String targetServiceUrl;
    private String registeredBaseUrl;
    private String dnsProbeDiscoveryUrl;
    /** EDGE when Mendr edge blocked; UPSTREAM when target service returned CORS rejection */
    private String corsBlockedAt;
    /** Origin header Mendr forwarded to the upstream service. */
    private String upstreamOriginSent;

    /** Correlation / request ids from the calling client (Phase 7 telemetry). */
    private String correlationId;
    private String requestId;
    /** W3C trace-context header propagated from the edge (init_v14 causal correlation). */
    private String traceparent;

    /** Optional upstream response headers captured at the edge. */
    private Map<String, Object> responseHeaders;

    /**
     * Optional RFC 9457 Problem Details envelope (dual-accepted with legacy fields).
     * Prefer {@code detail} / extensions when present; fall back to {@code errorMessage}.
     */
    private Map<String, Object> problemDetail;
    /** Number of duplicate edge reports suppressed in the current dedup window. */
    private Integer suppressedCount;
}
