package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.dto.RouteConfigSyncPayload;
import com.selfhealing.gateway.service.EdgeCapabilityTracker;
import com.selfhealing.gateway.service.RouteConfigSnapshotPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private static final long SYNC_TIMEOUT_MS = 30_000L;

    private final RouteConfigSnapshotPublisher snapshotPublisher;
    private final EdgeCapabilityTracker edgeCapabilityTracker;

    @GetMapping("/routeconfig")
    public Object syncRouteConfig(@RequestParam(name = "since", required = false) Long since,
                                  @RequestParam(name = "caps", required = false) String caps) {
        java.util.Set<String> capabilities = parseCaps(caps);
        edgeCapabilityTracker.record(capabilities);
        long current = snapshotPublisher.currentConfigVersion();
        if (since == null || since < current) {
            return ResponseEntity.ok(snapshotPublisher.buildFullSyncPayload(capabilities));
        }

        DeferredResult<ResponseEntity<RouteConfigSyncPayload>> deferred = new DeferredResult<>(SYNC_TIMEOUT_MS);
        deferred.onTimeout(() -> deferred.setResult(ResponseEntity.status(HttpStatus.NOT_MODIFIED).build()));
        snapshotPublisher.registerPendingSync(since, capabilities, deferred);
        return deferred;
    }

    /** Parse the comma-separated edge capability list (e.g. {@code ?caps=v2}). */
    private static java.util.Set<String> parseCaps(String caps) {
        if (caps == null || caps.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String c : caps.split(",")) {
            String t = c.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
