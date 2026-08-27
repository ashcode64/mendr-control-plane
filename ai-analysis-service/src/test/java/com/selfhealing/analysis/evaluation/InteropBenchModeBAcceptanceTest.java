package com.selfhealing.analysis.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InteropBenchModeBAcceptanceTest {

    @Test
    void throughputPilotMeasuresHttpTurnsAndAppliesBenchTenant() throws Exception {
        Path out = Path.of("target", "interop-baseline", "throughput-pilot.json");
        Map<String, Object> pilot = InteropBenchModeB.runThroughputPilot(out);
        assertEquals("throughput-pilot", pilot.get("label"));
        assertEquals(2, ((Number) pilot.get("diagnoseInvocations")).intValue());
        double measured = ((Number) pilot.get("measuredLlmHttpCallsPerDiagnose")).doubleValue();
        assertTrue(measured >= 1.0, "pilot must count ≥1 LLM HTTP turn, got " + measured);
        @SuppressWarnings("unchecked")
        Map<String, Object> applied = (Map<String, Object>) pilot.get("benchTenantApplied");
        assertEquals(120, ((Number) applied.get("tenantPerMinuteApplied")).intValue());
        assertEquals(300, ((Number) applied.get("globalPerMinuteApplied")).intValue());
        assertTrue(Files.exists(out));
    }

    @Test
    void modeBUsesN10IndependentDrawsAndNonZeroUsage() {
        InteropBenchUsageLedger.reset();
        InteropBenchModeB.Report post =
                InteropBenchModeB.runPostDetectors(InteropBenchFixtures.loadAll());
        assertEquals(10, post.notes().get("N"));
        assertEquals(true, post.notes().get("independentDraws"));
        assertTrue(post.totalTokens() > 0, "tokens must be recorded, got " + post.totalTokens());
        assertTrue(post.totalUsd() > 0, "USD must be recorded, got " + post.totalUsd());
        assertTrue(post.meanEqsatDelta() > 0, "EqSat Δ should be >0 from identity-scale pad, got "
                + post.meanEqsatDelta());
        assertTrue(post.cases().stream().allMatch(c -> c.n() == 10));
        assertTrue(post.valuePassAt1() >= 0.95, "post-detector value pass@1=" + post.valuePassAt1());
        assertFalse(Double.isNaN(post.shadowAttributionRate()));
        assertTrue(post.shadowAttributionRate() >= 0.90, "attr=" + post.shadowAttributionRate());
        assertTrue(post.cases().stream().noneMatch(c ->
                Boolean.TRUE.equals(c.shadowComparison().get("unflaggedDetectorWrongLlmRight"))));
    }

    @Test
    void modeBBaselineBIsWeakerThanPostOnValue() {
        InteropBenchModeB.Report b = InteropBenchModeB.runBaselineB(InteropBenchFixtures.loadAll());
        InteropBenchModeB.Report post = InteropBenchModeB.runPostDetectors(InteropBenchFixtures.loadAll());
        assertTrue(b.valuePassAt1() < 0.70,
                "LLM-only b should miss unit/date first-draw, b=" + b.valuePassAt1());
        double need = b.valuePassAt1() < 0.70 ? b.valuePassAt1() + 0.25 : 0.90;
        assertTrue(post.valuePassAt1() + 1e-9 >= need,
                "post=" + post.valuePassAt1() + " need≥" + need);
        assertTrue(post.passAt3() >= post.passAt1() - 1e-9);
    }

    @Test
    void shadowHelpersDoNotVacuousPass() {
        assertTrue(Double.isNaN(InteropBenchModeA.shadowAttributionRate(List.of())));
        Map<String, Object> vacuous = InteropBenchModeB.buildShadowPair(
                Map.of("ops", List.of(Map.of("op", "scale"))), null);
        assertEquals(false, vacuous.get("detectorBeatsOrTiesLlm"));
        assertEquals(true, vacuous.get("llmMissing"));
    }
}
