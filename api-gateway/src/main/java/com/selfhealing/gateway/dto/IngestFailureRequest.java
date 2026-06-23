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
}
