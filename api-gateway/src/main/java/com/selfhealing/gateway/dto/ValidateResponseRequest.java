package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Async response validation payload from OpenResty log.lua. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateResponseRequest {

    private String sourceService;
    private String targetService;
    private String endpoint;
    private String httpMethod;
    private int httpStatus;

    private Map<String, Object> requestPayload;
    private Map<String, Object> rawResponse;
    private Map<String, Object> transformedResponse;
    private Map<String, String> requestHeaders;

    /** Phase 7 — RFC 9457 + correlation telemetry from the edge. */
    private Map<String, Object> problemDetail;
    private String correlationId;
    private String requestId;
    private Map<String, Object> responseHeaders;
}
