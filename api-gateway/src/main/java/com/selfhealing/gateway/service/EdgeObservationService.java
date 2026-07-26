package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.EdgeObservationRequest;
import com.selfhealing.gateway.dto.EdgeObservationRequest.EdgeObservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ingests a batch of TRAFFIC_OBSERVED topology edges from the data plane and upserts
 * them (with accumulating call volume) through {@link TopologyGraphWriter}, then
 * rebuilds the content-addressed adjacency snapshot once for the batch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeObservationService {

    private final TopologyGraphWriter topologyGraphWriter;

    @Transactional
    public int ingest(EdgeObservationRequest request) {
        if (request == null || request.getObservations() == null || request.getObservations().isEmpty()) {
            return 0;
        }
        List<EdgeObservation> observations = request.getObservations();
        int applied = 0;
        for (EdgeObservation obs : observations) {
            if (obs == null || isBlank(obs.getSourceService()) || isBlank(obs.getTargetService())) {
                continue;
            }
            try {
                topologyGraphWriter.recordObservedEdge(
                        obs.getSourceService(), obs.getTargetService(),
                        obs.getEndpoint(), obs.getHttpMethod(), 1L);
                applied++;
            } catch (Exception e) {
                log.warn("Edge observation upsert failed for {}->{}{} — {}",
                        obs.getSourceService(), obs.getTargetService(), obs.getEndpoint(), e.getMessage());
            }
        }
        if (applied > 0) {
            topologyGraphWriter.rebuildSnapshot();
        }
        return applied;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
