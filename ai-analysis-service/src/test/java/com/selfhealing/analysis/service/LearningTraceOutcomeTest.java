package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LearningTraceOutcomeTest {

    @Test
    void readyAndHitlStayPendingForCheckConstraint() {
        assertEquals("PENDING", AiAnalysisService.resolveLearningOutcome(Map.of("status", "ready")));
        assertEquals("PENDING", AiAnalysisService.resolveLearningOutcome(Map.of(
                "status", "ready",
                "refuseAutoHeal", true)));
    }

    @Test
    void unverifiableMapsToFailure() {
        assertEquals("FAILURE", AiAnalysisService.resolveLearningOutcome(
                Map.of("status", "unverifiable")));
    }

    @Test
    void neverEmitsValuesOutsideDdlCheck() {
        String out = AiAnalysisService.resolveLearningOutcome(Map.of("status", "weird"));
        assertTrue(out.equals("PENDING") || out.equals("SUCCESS") || out.equals("FAILURE"));
        assertNotEquals("READY", out);
        assertNotEquals("HITL", out);
        assertNotEquals("UNVERIFIABLE", out);
    }
}
