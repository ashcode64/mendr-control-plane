package com.selfhealing.analysis.service.heuristics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeuristicReflectorCuratorJobTest {

    @Test
    void synthesizeFromCriticNullWhenBlank() {
        assertNull(HeuristicReflectorCuratorJob.synthesizeFromCritic("  ", Map.of()));
        assertNull(HeuristicReflectorCuratorJob.synthesizeFromCritic(null, null));
    }

    @Test
    void synthesizeDetectsRenameVsCoerceConfusion() {
        String h = HeuristicReflectorCuratorJob.synthesizeFromCritic(
                "Do not confuse rename with coerce here",
                Map.of("json_path", "/userId"));
        assertNotNull(h);
        assertTrue(h.contains("FIELD_RENAME"));
        assertTrue(h.contains("/userId"));
    }

    @Test
    void synthesizeProtectsAuthorizationFields() {
        String h = HeuristicReflectorCuratorJob.synthesizeFromCritic(
                "Authorization header must stay protected",
                Map.of());
        assertNotNull(h);
        assertTrue(h.toLowerCase().contains("protected"));
    }

    @Test
    void synthesizeDefaultWarnOffIncludesChangeType() {
        String h = HeuristicReflectorCuratorJob.synthesizeFromCritic(
                "This transform overfits the example payload",
                Map.of("change_type", "TYPE_COERCE", "json_path", "/age"));
        assertTrue(h.startsWith("Critic warn-off for TYPE_COERCE at /age"));
    }
}
