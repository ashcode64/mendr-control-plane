package com.selfhealing.analysis.evaluation;

import com.selfhealing.analysis.service.SchemaDiffResult;
import com.selfhealing.analysis.service.SchemaMismatchAnalyzer;
import com.selfhealing.analysis.service.registry.UnitDateDetector;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mode B: N independent mock-LLM diagnose draws (CI) or live LLM when
 * {@code -Dmendr.interop.mode-b=true} <em>and</em> a client is wired.
 * pass@k for k∈{1,3}; usage ledger; local EqSat Δ.
 */
public final class InteropBenchModeB {

    private InteropBenchModeB() {}

    public static final int DEFAULT_N = 10;
    public static final int PILOT_FIXTURE_COUNT = 2;

    public record CaseResult(
            String id,
            String clazz,
            boolean passAt1,
            boolean passAt3,
            int n,
            int hitsInN,
            double latencyMs,
            double tokens,
            double usdCost,
            double eqsatDelta,
            int llmHttpCalls,
            Map<String, Object> shadowComparison,
            String llmSource
    ) {}

    public record Report(
            List<CaseResult> cases,
            double passAt1,
            double passAt3,
            double valuePassAt1,
            double meanLatencyMs,
            double totalTokens,
            double totalUsd,
            double meanEqsatDelta,
            double shadowAttributionRate,
            Map<String, Object> notes
    ) {}

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("mendr.interop.mode-b", "false"));
    }

    public static Report runBaselineB(List<InteropBenchFixtures.Fixture> fixtures) {
        return run(fixtures, false, DEFAULT_N);
    }

    public static Report runPostDetectors(List<InteropBenchFixtures.Fixture> fixtures) {
        return run(fixtures, true, DEFAULT_N);
    }

    public static Report run(List<InteropBenchFixtures.Fixture> fixtures,
                             boolean detectorsEnabled, int n) {
        InteropBenchBenchTenant.apply();
        UnitDateDetector.DetectorConfig cfg = detectorsEnabled
                ? UnitDateDetector.DetectorConfig.defaults()
                : new UnitDateDetector.DetectorConfig(false, false, Set.of());

        List<CaseResult> cases = new ArrayList<>();
        List<Map<String, Object>> shadows = new ArrayList<>();
        double sumLat = 0, sumTok = 0, sumUsd = 0, sumEq = 0;
        int pass1 = 0, pass3 = 0, counted = 0;
        int valueN = 0, valuePass = 0;

        for (InteropBenchFixtures.Fixture f : fixtures) {
            if ("negative".equals(f.clazz())) continue;
            long t0 = System.nanoTime();
            InteropBenchUsageLedger.Snapshot before = InteropBenchUsageLedger.snapshot();

            SchemaDiffResult diff = SchemaMismatchAnalyzer.withDetectorConfig(cfg, () ->
                    SchemaMismatchAnalyzer.analyze(
                            f.input(), f.sourceSchema(), f.targetSchema(), null, null));
            Map<String, Object> detector = InteropBenchModeA.materializeProgram(diff);

            InteropBenchMockLlm.Sample firstLlm = null;
            boolean[] ok = new boolean[n];
            double eqAcc = 0;
            for (int k = 0; k < n; k++) {
                InteropBenchMockLlm.Sample llm = InteropBenchMockLlm.draw(f, k, n);
                if (k == 0) firstLlm = llm;
                Map<String, Object> candidate = llm.program();
                if (k == 0 && detectorsEnabled && diff.isRegistryDeterministic()
                        && diff.coverageComplete() && detector != null) {
                    candidate = detector;
                }
                Map<String, Object> minimized = InteropBenchLocalMinimize.minimize(candidate);
                eqAcc += InteropBenchLocalMinimize.delta(candidate, minimized);
                InteropBenchProductionVm.Result vm =
                        InteropBenchProductionVm.execute(minimized, f.input());
                @SuppressWarnings("unchecked")
                Map<String, Object> out = vm.ok() && vm.output() instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Map.of();
                ok[k] = vm.ok() && InteropBenchEvalExecutor.deepEqualsLoose(out, f.goldenOutput());
            }

            Map<String, Object> shadow = Map.of();
            if (detectorsEnabled && diff.isRegistryDeterministic() && firstLlm != null) {
                shadow = buildShadowPair(detector, firstLlm.program(),
                        okGolden(detector, f), okGolden(firstLlm.program(), f));
                shadows.add(shadow);
            }

            InteropBenchUsageLedger.Snapshot after = InteropBenchUsageLedger.snapshot();
            int http = after.httpCalls() - before.httpCalls();
            double tokens = (after.inputTokens() - before.inputTokens())
                    + (after.outputTokens() - before.outputTokens());
            double usd = after.usd() - before.usd();
            double lat = (System.nanoTime() - t0) / 1_000_000.0;
            double eqMean = n == 0 ? 0 : eqAcc / n;
            int hits = 0;
            for (boolean b : ok) if (b) hits++;
            boolean p1 = ok[0];
            boolean p3 = ok[0] || (n > 1 && ok[1]) || (n > 2 && ok[2]);

            cases.add(new CaseResult(f.id(), f.clazz(), p1, p3, n, hits, lat, tokens, usd, eqMean,
                    http, shadow,
                    enabled() ? "LIVE_REQUESTED_MOCK_FALLBACK" : "MOCK_LLM_N" + n));
            sumLat += lat;
            sumTok += tokens;
            sumUsd += usd;
            sumEq += eqMean;
            counted++;
            if (p1) pass1++;
            if (p3) pass3++;
            if ("value".equals(f.clazz())) {
                valueN++;
                if (p1) valuePass++;
            }
        }

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("mode", "B");
        notes.put("detectorsEnabled", detectorsEnabled);
        notes.put("N", n);
        notes.put("pass_at_k", List.of(1, 3));
        notes.put("llmSource", enabled() ? "LIVE_FLAG_SET_MOCK_HARNESS" : "MOCK_LLM");
        notes.put("benchTenant", InteropBenchBenchTenant.snapshot());
        notes.put("throughput", throughputNotes());
        notes.put("independentDraws", true);

        double attr = InteropBenchModeA.shadowAttributionRate(shadows);
        return new Report(
                cases,
                counted == 0 ? 0 : (double) pass1 / counted,
                counted == 0 ? 0 : (double) pass3 / counted,
                valueN == 0 ? 0 : (double) valuePass / valueN,
                counted == 0 ? 0 : sumLat / counted,
                sumTok,
                sumUsd,
                counted == 0 ? 0 : sumEq / counted,
                attr,
                notes
        );
    }

    public static Map<String, Object> runThroughputPilot(Path outFile) throws Exception {
        InteropBenchBenchTenant.apply();
        InteropBenchUsageLedger.reset();
        List<InteropBenchFixtures.Fixture> all = InteropBenchFixtures.loadAll();
        List<InteropBenchFixtures.Fixture> pilot = all.stream()
                .filter(f -> "value".equals(f.clazz()))
                .limit(PILOT_FIXTURE_COUNT)
                .toList();
        long t0 = System.nanoTime();
        int diagnoseInvocations = 0;
        List<Map<String, Object>> perFixture = new ArrayList<>();
        for (InteropBenchFixtures.Fixture f : pilot) {
            int httpBefore = InteropBenchUsageLedger.httpCalls();
            long ft0 = System.nanoTime();
            run(List.of(f), false, 1);
            diagnoseInvocations++;
            int calls = InteropBenchUsageLedger.httpCalls() - httpBefore;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.id());
            row.put("wallMs", (System.nanoTime() - ft0) / 1_000_000.0);
            row.put("measuredLlmHttpCalls", calls);
            perFixture.add(row);
        }
        double wallMs = (System.nanoTime() - t0) / 1_000_000.0;
        double avgCalls = diagnoseInvocations == 0 ? 0
                : (double) InteropBenchUsageLedger.httpCalls() / diagnoseInvocations;

        int fixturesForSweep = (int) all.stream().filter(f -> !"negative".equals(f.clazz())).count();
        double estimatedDiagnoses = fixturesForSweep * (double) DEFAULT_N * 2;
        double estimatedLlmCalls = estimatedDiagnoses * avgCalls;
        double wallMinAtProdLimit = estimatedDiagnoses / InteropBenchBenchTenant.PRODUCTION_TENANT_PER_MIN;
        double wallMinAtBench = estimatedDiagnoses / InteropBenchBenchTenant.TENANT_PER_MIN;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", "throughput-pilot");
        payload.put("frozenAt", Instant.now().toString());
        payload.put("pilotFixtures", perFixture);
        payload.put("diagnoseInvocations", diagnoseInvocations);
        payload.put("measuredLlmHttpCallsPerDiagnose", avgCalls);
        payload.put("estimatedLlmHttpCallsPerDiagnose", avgCalls);
        payload.put("pilotWallMs", wallMs);
        payload.put("admissionDefaults", Map.of(
                "semaphore", InteropBenchBenchTenant.SEMAPHORE,
                "tenantPerMinute", InteropBenchBenchTenant.PRODUCTION_TENANT_PER_MIN,
                "globalPerMinute", InteropBenchBenchTenant.PRODUCTION_GLOBAL_PER_MIN));
        payload.put("benchTenantApplied", InteropBenchBenchTenant.snapshot());
        payload.put("fullSweepSizing", Map.of(
                "crossedFixtures", fixturesForSweep,
                "N", DEFAULT_N,
                "sweeps", 2,
                "estimatedDiagnoses", estimatedDiagnoses,
                "estimatedLlmHttpCalls", estimatedLlmCalls,
                "estimatedWallMinutesAtTenantLimit10", wallMinAtProdLimit,
                "estimatedWallMinutesAtBenchTenant", wallMinAtBench,
                "recommendation", wallMinAtProdLimit > 60
                        ? "Use application-interop.yml (tenant=120) before Mode B N=10"
                        : "Default limits may suffice"));
        if (outFile != null) {
            InteropBenchBaseline.writeIfAllowed(outFile, payload);
        }
        return payload;
    }

    public static Map<String, Object> freeze(Path outFile, Report report, String label) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", label);
        payload.put("frozenAt", Instant.now().toString());
        payload.put("immutable", true);
        payload.put("rewriteOnTest", false);
        payload.put("summary", toSummary(report));
        payload.put("cases", report.cases());
        if (outFile != null) {
            InteropBenchBaseline.writeIfAllowed(outFile, payload);
        }
        return payload;
    }

    static Map<String, Object> buildShadowPair(
            Map<String, Object> detector, Map<String, Object> llm) {
        return buildShadowPair(detector, llm, false, false);
    }

    static Map<String, Object> buildShadowPair(
            Map<String, Object> detector, Map<String, Object> llm,
            boolean detectorCorrect, boolean llmCorrect) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object dOps = detector != null ? detector.get("ops") : null;
        Object lOps = llm != null ? llm.get("ops") : null;
        out.put("detectorOps", dOps);
        out.put("llmOps", lOps);
        boolean match = dOps != null && lOps != null && dOps.equals(lOps);
        out.put("programsMatch", match);
        // Beat = detector correct and LLM not, or exact match. Vacuous LLM-missing is not a beat.
        boolean beats = match || (detectorCorrect && !llmCorrect && lOps instanceof List<?> list && !list.isEmpty());
        if (!beats && detectorCorrect && !llmCorrect && lOps instanceof List<?> list && list.isEmpty()) {
            // LLM abstained with empty ops — detector still attributed as beating abstention
            beats = detectorCorrect;
        }
        out.put("detectorBeatsOrTiesLlm", beats);
        out.put("llmMissing", lOps == null);
        out.put("unflaggedDetectorWrongLlmRight", !detectorCorrect && llmCorrect);
        return out;
    }

    private static boolean okGolden(Map<String, Object> program, InteropBenchFixtures.Fixture f) {
        if (program == null) return false;
        Map<String, Object> min = InteropBenchLocalMinimize.minimize(program);
        InteropBenchProductionVm.Result vm = InteropBenchProductionVm.execute(min, f.input());
        if (!vm.ok() || !(vm.output() instanceof Map<?, ?> m)) return false;
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m;
        return InteropBenchEvalExecutor.deepEqualsLoose(out, f.goldenOutput());
    }

    public static Map<String, Object> toSummary(Report report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pass@1", report.passAt1());
        summary.put("pass@3", report.passAt3());
        summary.put("value_pass@1", report.valuePassAt1());
        summary.put("mean_latency_ms", report.meanLatencyMs());
        summary.put("total_tokens", report.totalTokens());
        summary.put("total_usd", report.totalUsd());
        summary.put("mean_eqsat_delta", report.meanEqsatDelta());
        summary.put("shadow_attribution_rate", report.shadowAttributionRate());
        summary.put("n", report.cases().size());
        summary.put("notes", report.notes());
        return summary;
    }

    public static String throughputNotes() {
        return """
                Mode B throughput: production tenant-per-minute=10, global-per-minute=30, semaphore=2.
                Bench tenant applied via InteropBenchBenchTenant / application-interop.yml:
                  tenant-per-minute=120, global-per-minute=300.
                Pilot measures real mock-LLM HTTP turns (not a hardcoded 6).
                pass@k reported for k in {1,3}; N=10 independent draws per fixture.
                """;
    }
}
