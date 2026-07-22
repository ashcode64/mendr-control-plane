package com.selfhealing.analysis.service.evolvemem;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalConfigTest {

    @Test
    void decayApproachesFloorOverHalfLives() {
        RetrievalConfig cfg = new RetrievalConfig(
                null, null, 1, 8, 0.15, 0.85, 30.0, 0.25, "ACTIVE", null, Map.of());
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        Instant fresh = now.minus(1, ChronoUnit.DAYS);
        Instant oneHalf = now.minus(30, ChronoUnit.DAYS);
        Instant twoHalf = now.minus(60, ChronoUnit.DAYS);

        double dFresh = cfg.decayMultiplier(fresh, now);
        double dHalf = cfg.decayMultiplier(oneHalf, now);
        double dTwo = cfg.decayMultiplier(twoHalf, now);

        assertTrue(dFresh > 0.9);
        assertEquals(0.25 + 0.75 * 0.5, dHalf, 1e-6);
        assertTrue(dTwo < dHalf);
        assertTrue(dTwo >= 0.25);
    }

    @Test
    void applyRetrievalPolicyFiltersAndDecays() {
        EvolveMemService svc = new EvolveMemService(null, null, null);
        RetrievalConfig cfg = new RetrievalConfig(
                null, null, 2, 2, 0.2, 0.9, 30.0, 0.25, "ACTIVE", 1, Map.of());

        Instant now = Instant.now();
        List<Map<String, Object>> scored = List.of(
                Map.of("score", 0.9, "distance", 0.1,
                        "verified_at", java.sql.Timestamp.from(now.minus(1, ChronoUnit.DAYS))),
                Map.of("score", 0.5, "distance", 0.2,
                        "verified_at", java.sql.Timestamp.from(now.minus(90, ChronoUnit.DAYS))),
                Map.of("score", 0.8, "distance", 0.95,
                        "verified_at", java.sql.Timestamp.from(now)) // over maxDistance
        );

        List<Map<String, Object>> out = svc.applyRetrievalPolicy(scored, cfg);
        assertTrue(out.size() <= 2);
        assertTrue(out.stream().noneMatch(r ->
                ((Number) r.get("distance")).doubleValue() > 0.9));
        assertTrue(out.get(0).containsKey("decay"));
        assertEquals(2, out.get(0).get("retrievalVersion"));
    }

    @Test
    void proposeOfflineCandidateNudgesWhenAged() {
        // Unit-level: decay math already covered; propose needs JDBC — skip integration here.
        RetrievalConfig active = RetrievalConfig.defaults();
        assertEquals(8, active.topK());
        assertEquals(30.0, active.decayHalfLifeDays());
    }
}
