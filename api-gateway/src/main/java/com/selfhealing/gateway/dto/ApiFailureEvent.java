package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiFailureEvent {
    private UUID failureId;
    private String serviceA;
    private String serviceB;
    private String endpoint;
    private String httpMethod;
    private int errorCode;
    private String errorType;
    private Map<String, Object> requestPayload;
    private Map<String, Object> responsePayload;
    private String errorMessage;
    private LocalDateTime timestamp;

    // ── Dynamic Routing context ──────────────────────────────
    private String failureCategory;   // SCHEMA_MISMATCH | ROUTING | CORS | UNKNOWN
    private String attemptedUrl;      // full URL that was attempted (for routing failures)
    private String requestOrigin;     // Origin header value (for CORS failures)
    private String targetServiceUrl;      // resolved URL at time of failure
    private String registeredBaseUrl;     // base_url from services registry at failure time
    private String dnsProbeDiscoveryUrl;  // first reachable URL from DnsProbeService.discoverNewUrl
    /** EDGE | UPSTREAM — where CORS was enforced */
    private String corsBlockedAt;
    /** Origin Mendr sent to Service B on the wire. */
    private String upstreamOriginSent;
    /** Client correlation / request id from the edge (Phase 7). */
    private String correlationId;
    private String requestId;
    /** RFC 9457 Problem Details (dual-accept with errorMessage). */
    private Map<String, Object> problemDetail;
    /** Selected upstream response headers from the edge. */
    private Map<String, Object> responseHeaders;
}
