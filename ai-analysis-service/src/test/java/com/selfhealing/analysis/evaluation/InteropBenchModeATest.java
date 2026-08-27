package com.selfhealing.analysis.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InteropBenchModeATest {

    @Test
    void fixturesLoadAtLeastThirty() {
        List<InteropBenchFixtures.Fixture> fixtures = InteropBenchFixtures.loadAll();
        assertTrue(fixtures.size() >= 30, "expected ≥30 fixtures, got " + fixtures.size());
        long negatives = fixtures.stream().filter(f -> "negative".equals(f.clazz())).count();
        long values = fixtures.stream().filter(f -> "value".equals(f.clazz())).count();
        assertTrue(negatives >= 7);
        assertTrue(values >= 12);
        assertTrue(fixtures.stream().anyMatch(f ->
                "value".equals(f.clazz()) && "BOUNDED_WINDOW".equals(f.planClass())));
        assertTrue(fixtures.stream().anyMatch(f ->
                "value".equals(f.clazz()) && "UNBOUNDED".equals(f.planClass())));
        assertTrue(fixtures.stream().anyMatch(f ->
                f.id().contains("c_to_f") || f.id().contains("celsius_to_f")));
    }

    @Test
    void modeAZeroFalsePositivesOnNegatives() {
        InteropBenchModeA.Report report = InteropBenchModeA.run(InteropBenchFixtures.loadAll());
        assertEquals(0, report.negativeFalsePositives(),
                "D7 precision: zero false positives on negative fixtures");
    }

    @Test
    void modeAValuePassMeetsAcceptanceBar() {
        InteropBenchModeA.Report report = InteropBenchModeA.run(InteropBenchFixtures.loadAll());
        assertTrue(report.valuePassAt1() >= 0.95,
                "value pass@1=" + report.valuePassAt1()
                        + " summary=" + InteropBenchModeA.toMarkdownTable(report)
                        + " fails=" + report.cases().stream()
                        .filter(c -> "value".equals(c.clazz()) && !c.pass())
                        .map(c -> c.id() + ":" + c.notes())
                        .toList());
    }

    @Test
    void modeAUsesProductionVmAndReportsValueOps() {
        InteropBenchModeA.Report report = InteropBenchModeA.run(InteropBenchFixtures.loadAll());
        Map<String, Object> summary = InteropBenchModeA.toMarkdownTable(report);
        assertEquals("MendrScriptExecutor", summary.get("executor"));
        @SuppressWarnings("unchecked")
        Map<String, Double> byOp = (Map<String, Double>) summary.get("pass_by_op_class");
        assertTrue(byOp.containsKey("scale") || byOp.containsKey("reformat_date") || byOp.containsKey("arith"),
                "per-op-class must include value ops, got " + byOp.keySet());
    }

    @Test
    void modeAStructuralUsesAnalyzerNotOracle() {
        InteropBenchModeA.Report report = InteropBenchModeA.run(InteropBenchFixtures.loadAll());
        long structPass = report.cases().stream()
                .filter(c -> "structural".equals(c.clazz()) && c.pass())
                .count();
        assertTrue(structPass >= 6, "structural pass count=" + structPass
                + " structural_pass@1=" + report.structuralPassAt1());
        // s_coerce_int should pass after float→int TYPE_COERCE detection
        assertTrue(report.cases().stream()
                        .filter(c -> "s_coerce_int_medium_fo".equals(c.id()))
                        .anyMatch(InteropBenchModeA.CaseResult::pass),
                "s_coerce_int_medium_fo should pass");
    }

    @Test
    void modeAMetricsPresent() {
        InteropBenchModeA.Report report = InteropBenchModeA.run(InteropBenchFixtures.loadAll());
        Map<String, Object> summary = InteropBenchModeA.toMarkdownTable(report);
        assertTrue(summary.containsKey("mean_field_f1"));
        assertTrue(summary.containsKey("mean_value_accuracy"));
        assertTrue(summary.containsKey("pass_by_op_class"));
        assertTrue(summary.containsKey("pass_by_cell"));
        assertEquals(0, summary.get("llm_calls"));
        assertNull(summary.get("eqsat_delta"));
    }

    @Test
    void freezeBaselineArtifactWithAndWithoutDetectorsWritesTargetOnly() throws Exception {
        Path dir = Path.of("target", "interop-baseline");
        Files.createDirectories(dir);
        Map<String, Object> pre = InteropBenchBaseline.freezeWithoutDetectors(
                dir.resolve("mode-a-pre-detector.json"));
        Map<String, Object> post = InteropBenchBaseline.freeze(
                dir.resolve("mode-a-post-detector.json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> preSum = (Map<String, Object>) pre.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> postSum = (Map<String, Object>) post.get("summary");
        double preValue = ((Number) preSum.get("value_pass@1")).doubleValue();
        double postValue = ((Number) postSum.get("value_pass@1")).doubleValue();
        assertTrue(postValue >= 0.95, "post value=" + postValue);
        assertTrue(postValue > preValue || preValue >= 0.95);
        assertTrue(Files.exists(dir.resolve("mode-a-post-detector.json")));
    }

    @Test
    void shadowAttributionBarHelperRejectsEmpty() {
        assertTrue(Double.isNaN(InteropBenchModeA.shadowAttributionRate(List.of())));
        assertTrue(InteropBenchModeA.shadowAttributionRate(List.of(
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", true, "detectorBeatsOrTiesLlm", true),
                Map.of("programsMatch", false, "detectorBeatsOrTiesLlm", false)
        )) >= 0.90);
    }

    @Test
    void modeBDoesNotGateCiByDefault() {
        assertFalse(InteropBenchModeB.enabled());
        assertTrue(InteropBenchModeB.throughputNotes().contains("tenant-per-minute"));
    }
}
