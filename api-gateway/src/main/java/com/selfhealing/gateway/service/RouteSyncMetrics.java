package com.selfhealing.gateway.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for route-config sync health (outbox relay, reconciler, overlay drift).
 */
@Component
public class RouteSyncMetrics {

    private final MeterRegistry registry;

    public RouteSyncMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOutboxProcessed() {
        registry.counter("mendr.sync.outbox.processed").increment();
    }

    public void recordOutboxFailed() {
        registry.counter("mendr.sync.outbox.failed").increment();
    }

    public void recordOutboxExhausted() {
        registry.counter("mendr.sync.outbox.exhausted").increment();
    }

    public void recordReconcilerRepair() {
        registry.counter("mendr.sync.reconciler.repairs").increment();
    }

    public void recordOverlayDrift() {
        registry.counter("mendr.sync.overlay.drift").increment();
    }
}
