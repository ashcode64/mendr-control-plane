package com.selfhealing.analysis.service.evolvemem;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned retrieval knobs for EvolveMem (Phase 5).
 */
public record RetrievalConfig(
        UUID id,
        UUID tenantId,
        int version,
        int topK,
        double minScore,
        double maxDistance,
        double decayHalfLifeDays,
        double decayFloor,
        String status,
        Integer parentVersion,
        Map<String, Object> metrics
) {
    public static RetrievalConfig defaults() {
        return new RetrievalConfig(
                null, null, 0, 8, 0.15, 0.85, 30.0, 0.25, "ACTIVE", null, Map.of());
    }

    /**
     * Exponential relevance decay toward {@code decayFloor}.
     * half-life days → multiplier = floor + (1-floor) * 0.5^(age/halfLife)
     */
    public double decayMultiplier(Instant eventTime, Instant now) {
        if (eventTime == null || decayHalfLifeDays <= 0) return 1.0;
        Instant n = now == null ? Instant.now() : now;
        long ageDays = Math.max(0, ChronoUnit.DAYS.between(eventTime, n));
        double half = decayHalfLifeDays;
        double keep = Math.pow(0.5, ageDays / half);
        return decayFloor + (1.0 - decayFloor) * keep;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tenantId", tenantId);
        m.put("version", version);
        m.put("topK", topK);
        m.put("minScore", minScore);
        m.put("maxDistance", maxDistance);
        m.put("decayHalfLifeDays", decayHalfLifeDays);
        m.put("decayFloor", decayFloor);
        m.put("status", status);
        m.put("parentVersion", parentVersion);
        if (metrics != null && !metrics.isEmpty()) m.put("metrics", metrics);
        return m;
    }
}
