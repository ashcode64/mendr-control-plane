package com.selfhealing.gateway.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Remembers the most recent edge capability advertisement from sync so
 * single-route republish strips policy blocks consistently with full sync.
 */
@Component
public class EdgeCapabilityTracker {

    private final AtomicReference<Set<String>> lastCaps = new AtomicReference<>(Set.of());

    public void record(Set<String> caps) {
        if (caps != null && !caps.isEmpty()) {
            lastCaps.set(Set.copyOf(caps));
        }
    }

    public Set<String> lastSeen() {
        return lastCaps.get();
    }

    public boolean hasCap(String cap) {
        return lastCaps.get().contains(cap);
    }
}
