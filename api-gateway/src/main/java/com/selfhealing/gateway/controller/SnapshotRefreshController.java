package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.service.RouteConfigSnapshotPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class SnapshotRefreshController {

    private final RouteConfigSnapshotPublisher snapshotPublisher;

    @PostMapping("/refresh-snapshots")
    public ResponseEntity<Map<String, Object>> refreshSnapshots() {
        snapshotPublisher.publishAllDistinctRoutes();
        return ResponseEntity.ok(Map.of("status", "refreshed"));
    }
}
