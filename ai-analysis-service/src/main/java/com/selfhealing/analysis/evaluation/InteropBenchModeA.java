package com.selfhealing.analysis.evaluation;

import com.selfhealing.analysis.service.SchemaDiffResult;
import com.selfhealing.analysis.service.SchemaMismatchAnalyzer;
import com.selfhealing.analysis.service.registry.UnitDateDetector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mode A: deterministic analyzer only — never uses expected_program as a success oracle,
 * never calls an LLM. Executes via {@link InteropBenchProductionVm} (Java MendrScript VM).
 */
public final class InteropBenchModeA {

    private InteropBenchModeA() {}

    public record CaseResult(
            String id,
            String clazz,
            String llmDifficulty,
            String planClass,
            boolean pass,
            boolean detectorFired,
            String detectedKind,
            double fieldF1,
            double valueAccuracy,
            String primaryOp,
            String notes
    ) {}

    public record Report(
            List<CaseResult> cases,
            double passAt1,
            double valuePassAt1,
            double structuralPassAt1,
            int negativeFalsePositives,
            double meanFieldF1,
            double meanValueAccuracy,
            Map<String, Double> passByOpClass,
            Map<String, Double> passByCell
    ) {}

    public static Report run(List<InteropBenchFixtures.Fixture> fixtures) {
        return run(fixtures, UnitDateDetector.DetectorConfig.defaults());
    }

    /** Run with detectors forced off — surrogate pre-detector baseline (D2). */
    public static Report runWithoutDetectors(List<InteropBenchFixtures.Fixture> fixtures) {
        return run(fixtures, new UnitDateDetector.DetectorConfig(false, false, Set.of()));
    }

    public static Report run(List<InteropBenchFixtures.Fixture> fixtures,
                             UnitDateDetector.DetectorConfig detectorConfig) {
        List<CaseResult> results = new ArrayList<>();
        int pass = 0;
        int valuePass = 0, valueN = 0;
        int structPass = 0, structN = 0;
        int negFp = 0;
        double sumF1 = 0, sumVa = 0;
        int metricN = 0;
        Map<String, int[]> opClass = new LinkedHashMap<>();
        Map<String, int[]> cells = new LinkedHashMap<>();

        for (InteropBenchFixtures.Fixture f : fixtures) {
            SchemaDiffResult diff = SchemaMismatchAnalyzer.withDetectorConfig(detectorConfig, () ->
                    SchemaMismatchAnalyzer.analyze(
                            f.input(), f.sourceSchema(), f.targetSchema(), null, null));

            boolean detectorFired = diff.isRegistryDeterministic();
            String cell = f.llmDifficulty() + "|" + f.planClass() + "|" + f.clazz();

            if ("negative".equals(f.clazz()) && f.assertNoP0Detector() && detectorFired) {
                negFp++;
                results.add(new CaseResult(f.id(), f.clazz(), f.llmDifficulty(), f.planClass(),
                        false, true, diff.kind().name(), 0, 0, null, "false-positive detector"));
                continue;
            }
            if ("negative".equals(f.clazz())) {
                boolean ok = !detectorFired;
                results.add(new CaseResult(f.id(), f.clazz(), f.llmDifficulty(), f.planClass(),
                        ok, detectorFired, diff.kind().name(), 1, 1, null,
                        ok ? "negative ok" : "unexpected fire"));
                if (ok) pass++;
                continue;
            }

            Map<String, Object> program = materializeProgram(diff);
            if (program == null || !(program.get("ops") instanceof List<?> ops) || ops.isEmpty()) {
                // Count no-program as 0 F1 / 0 VA (do not inflate means by skipping).
                results.add(new CaseResult(f.id(), f.clazz(), f.llmDifficulty(), f.planClass(),
                        false, detectorFired, diff.kind().name(), 0, 0, null, "no analyzer program"));
                sumF1 += 0;
                sumVa += 0;
                metricN++;
                bump(cells, cell, false);
                if ("value".equals(f.clazz())) valueN++;
                if ("structural".equals(f.clazz())) structN++;
                continue;
            }

            InteropBenchProductionVm.Result vm = InteropBenchProductionVm.execute(program, f.input());
            @SuppressWarnings("unchecked")
            Map<String, Object> out = vm.ok() && vm.output() instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            boolean ok = vm.ok() && InteropBenchEvalExecutor.deepEqualsLoose(out, f.goldenOutput());
            double f1 = InteropBenchEvalExecutor.fieldF1(out, f.goldenOutput());
            double va = InteropBenchEvalExecutor.valueAccuracy(out, f.goldenOutput());
            String primaryOp = primaryOp(program);
            String notes = ok ? "pass"
                    : (!vm.ok() ? "vm/verify: " + vm.errors() : "golden mismatch");
            results.add(new CaseResult(f.id(), f.clazz(), f.llmDifficulty(), f.planClass(),
                    ok, detectorFired, diff.kind().name(), f1, va, primaryOp, notes));

            sumF1 += f1;
            sumVa += va;
            metricN++;
            bump(opClass, primaryOp == null ? "unknown" : primaryOp, ok);
            bump(cells, cell, ok);

            if (ok) pass++;
            if ("value".equals(f.clazz())) {
                valueN++;
                if (ok) valuePass++;
            }
            if ("structural".equals(f.clazz())) {
                structN++;
                if (ok) structPass++;
            }
        }

        return new Report(
                results,
                results.isEmpty() ? 0 : (double) pass / results.size(),
                valueN == 0 ? 0 : (double) valuePass / valueN,
                structN == 0 ? 0 : (double) structPass / structN,
                negFp,
                metricN == 0 ? 0 : sumF1 / metricN,
                metricN == 0 ? 0 : sumVa / metricN,
                rates(opClass),
                rates(cells)
        );
    }

    /** Legacy rule maps → MendrScript ops (analyzer path only — no expected_program). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> materializeProgram(SchemaDiffResult diff) {
        if (diff == null || !diff.hasDeterministicRule()) return null;
        if (diff.isRegistryDeterministic()) {
            return diff.toTransformationRules();
        }
        Map<String, Object> rules = diff.toTransformationRules();
        List<Map<String, Object>> ops = new ArrayList<>();
        String type = String.valueOf(rules.get("type"));
        if ("FIELD_RENAME".equals(type) && rules.get("mappings") instanceof Map<?, ?> maps) {
            for (Map.Entry<?, ?> e : maps.entrySet()) {
                ops.add(Map.of("op", "rename",
                        "from", "/" + e.getKey(), "to", "/" + e.getValue()));
            }
        } else if ("TYPE_COERCE".equals(type) && rules.get("coercions") instanceof Map<?, ?> c) {
            for (Map.Entry<?, ?> e : c.entrySet()) {
                ops.add(Map.of("op", "coerce", "path", "/" + e.getKey(),
                        "targetType", String.valueOf(e.getValue())));
            }
        } else if ("ADD_DEFAULT".equals(type) && rules.get("defaults") instanceof Map<?, ?> d) {
            for (Map.Entry<?, ?> e : d.entrySet()) {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "default");
                op.put("path", "/" + e.getKey());
                op.put("value", e.getValue());
                op.put("on", "ABSENT");
                ops.add(op);
            }
        } else if ("FIELD_MOVE".equals(type) && rules.get("moves") instanceof List<?> moves) {
            for (Object m : moves) {
                if (m instanceof Map<?, ?> move) {
                    ops.add(Map.of("op", "move",
                            "from", String.valueOf(move.get("from")),
                            "to", String.valueOf(move.get("to"))));
                }
            }
        }
        if (ops.isEmpty()) return null;
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("schemaVersion", "mendrscript/v1");
        program.put("ops", ops);
        return program;
    }

    /** Prefer value-mutating op when present; otherwise first op. */
    static String primaryOp(Map<String, Object> program) {
        Object ops = program.get("ops");
        if (!(ops instanceof List<?> list) || list.isEmpty()) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) instanceof Map<?, ?> m) {
                String op = String.valueOf(m.get("op"));
                if ("scale".equals(op) || "reformat_date".equals(op) || "arith".equals(op)
                        || "coerce".equals(op) || "map_value".equals(op)) {
                    return op;
                }
            }
        }
        if (list.get(0) instanceof Map<?, ?> m) {
            return String.valueOf(m.get("op"));
        }
        return null;
    }

    private static void bump(Map<String, int[]> map, String key, boolean ok) {
        int[] a = map.computeIfAbsent(key, k -> new int[2]);
        a[1]++;
        if (ok) a[0]++;
    }

    private static Map<String, Double> rates(Map<String, int[]> map) {
        Map<String, Double> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, v[1] == 0 ? 0.0 : (double) v[0] / v[1]));
        return out;
    }

    public static Map<String, Object> toMarkdownTable(Report report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pass@1", report.passAt1());
        summary.put("value_pass@1", report.valuePassAt1());
        summary.put("structural_pass@1", report.structuralPassAt1());
        summary.put("negative_false_positives", report.negativeFalsePositives());
        summary.put("mean_field_f1", report.meanFieldF1());
        summary.put("mean_value_accuracy", report.meanValueAccuracy());
        summary.put("pass_by_op_class", report.passByOpClass());
        summary.put("pass_by_cell", report.passByCell());
        summary.put("n", report.cases().size());
        summary.put("llm_calls", 0);
        summary.put("mode", "A");
        summary.put("executor", "MendrScriptExecutor");
        summary.put("eqsat_delta", null); // Mode A does not minimize; see Mode B
        summary.put("shadow_attribution_min", 0.90);
        return summary;
    }

    /**
     * D6 acceptance: fraction of non-empty shadow pairs where detector matches or
     * beats a real LLM proposal. Empty collection is undefined → NaN (must not pass bars).
     */
    public static double shadowAttributionRate(java.util.Collection<Map<String, Object>> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return Double.NaN;
        }
        int ok = 0;
        for (Map<String, Object> p : pairs) {
            if (Boolean.TRUE.equals(p.get("programsMatch"))
                    || Boolean.TRUE.equals(p.get("detectorBeatsOrTiesLlm"))) {
                ok++;
            }
        }
        return (double) ok / pairs.size();
    }
}
