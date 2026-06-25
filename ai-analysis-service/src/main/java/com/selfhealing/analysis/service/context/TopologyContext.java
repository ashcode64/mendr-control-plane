package com.selfhealing.analysis.service.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Manifest-derived service dependency graph for the failing route, built from
 * {@code service_routes} + {@code service_contracts.description}. Gives the model
 * the neighborhood a failure lives in (who calls whom, with what intent) instead
 * of just the two endpoints of the failing call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TopologyContext(
        Edge failingCall,
        List<Edge> sourceOutboundCalls,
        List<Edge> targetInboundCallers
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(
            String sourceService,
            String targetService,
            String endpoint,
            String httpMethod,
            String description
    ) {
    }

    public boolean isEmpty() {
        return failingCall == null
                && (sourceOutboundCalls == null || sourceOutboundCalls.isEmpty())
                && (targetInboundCallers == null || targetInboundCallers.isEmpty());
    }
}
