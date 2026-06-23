package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.dto.RouteConfigSyncPayload;
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

    @GetMapping("/routeconfig")
    public Object syncRouteConfig(@RequestParam(name = "since", required = false) Long since) {
        long current = snapshotPublisher.currentConfigVersion();
        if (since == null || since < current) {
            return ResponseEntity.ok(snapshotPublisher.buildFullSyncPayload());
        }

        DeferredResult<ResponseEntity<RouteConfigSyncPayload>> deferred = new DeferredResult<>(SYNC_TIMEOUT_MS);
        deferred.onTimeout(() -> deferred.setResult(ResponseEntity.status(HttpStatus.NOT_MODIFIED).build()));
        snapshotPublisher.registerPendingSync(since, deferred);
        return deferred;
    }
}
