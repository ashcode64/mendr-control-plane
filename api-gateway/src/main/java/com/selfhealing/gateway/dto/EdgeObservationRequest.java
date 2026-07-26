package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Batch of observed topology edges reported by the data-plane {@code log.lua}
 * (TRAFFIC_OBSERVED tier). Caller->callee is attributed by Mendr's own routing
 * envelope; the trace-context fields ride along for downstream causal correlation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdgeObservationRequest {

    private List<EdgeObservation> observations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EdgeObservation {
        private String sourceService;
        private String targetService;
        private String endpoint;
        private String httpMethod;
        private Integer statusCode;
        private String correlationId;
        private String requestId;
        private String traceparent;
        private String observedAt;
    }
}
