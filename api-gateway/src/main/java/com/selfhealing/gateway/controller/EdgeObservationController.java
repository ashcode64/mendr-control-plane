package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.dto.EdgeObservationRequest;
import com.selfhealing.gateway.service.EdgeObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal endpoint (guarded like the rest of {@code /api/internal/**} by the shared
 * {@code X-Internal-Api-Key} / edge credential) that receives sampled TRAFFIC_OBSERVED
 * edge reports from the data-plane {@code log.lua}.
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class EdgeObservationController {

    private final EdgeObservationService edgeObservationService;

    @PostMapping("/edge-observations")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody EdgeObservationRequest request) {
        int applied = edgeObservationService.ingest(request);
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "applied", applied));
    }
}
