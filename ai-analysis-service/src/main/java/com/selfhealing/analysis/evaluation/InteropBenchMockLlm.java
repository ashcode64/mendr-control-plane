package com.selfhealing.analysis.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeded mock LLM for InteropBench Mode B N=10. Each sample is an independent
 * diagnose draw (not a repeated identical VM execute). Records HTTP-turn usage
 * on {@link InteropBenchUsageLedger}.
 */
public final class InteropBenchMockLlm {

    private InteropBenchMockLlm() {}

    public record Sample(Map<String, Object> program, int httpTurns, int inputTokens, int outputTokens) {}

    /**
     * Draw sample {@code k} in {@code 0..n-1} for a fixture.
     * Value-class first samples are intentionally weak (pass@1 low without detectors).
     */
    public static Sample draw(InteropBenchFixtures.Fixture f, int k, int n) {
        int turns = 1 + Math.floorMod(f.id().hashCode() + k, 3); // 1–3 HTTP turns
        int inTok = 400 + Math.abs((f.id() + k).hashCode() % 400);
        int outTok = 80 + Math.abs((f.id() + ":" + k).hashCode() % 120);
        for (int t = 0; t < turns; t++) {
            InteropBenchUsageLedger.recordHttpCall(inTok / turns, outTok / turns);
        }

        Map<String, Object> program = proposalFor(f, k, n);
        return new Sample(program, turns, inTok, outTok);
    }

    /**
     * Value fixtures: k=0 fails (empty/wrong); later k may emit expected_program
     * padded with an identity scale so EqSat Δ is measurable.
     * Structural: even k emits expected_program; odd k may omit an op.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> proposalFor(InteropBenchFixtures.Fixture f, int k, int n) {
        Map<String, Object> expected = f.expectedProgram();
        if ("value".equals(f.clazz())) {
            if (k == 0) {
                // First draw abstains / wrong type — typical LLM miss on unit/date.
                return Map.of("schemaVersion", "mendrscript/v1", "ops", List.of());
            }
            if (k == 1 && "hard".equals(f.llmDifficulty())) {
                return Map.of("schemaVersion", "mendrscript/v1", "ops", List.of());
            }
            return padForMinimize(expected);
        }
        if (expected == null) {
            return Map.of("schemaVersion", "mendrscript/v1", "ops", List.of());
        }
        if (k % 4 == 3) {
            // Occasional incomplete structural proposal
            Object ops = expected.get("ops");
            if (ops instanceof List<?> list && list.size() > 1) {
                Map<String, Object> p = new LinkedHashMap<>(expected);
                p.put("ops", List.of(list.get(0)));
                return p;
            }
        }
        return padForMinimize(expected);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> padForMinimize(Map<String, Object> program) {
        if (program == null) {
            return Map.of("schemaVersion", "mendrscript/v1", "ops", List.of());
        }
        Map<String, Object> out = new LinkedHashMap<>(program);
        List<Map<String, Object>> ops = new ArrayList<>();
        Object raw = program.get("ops");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    ops.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        if (!ops.isEmpty()) {
            String path = firstPath(ops.get(0));
            Map<String, Object> ident = new LinkedHashMap<>();
            ident.put("op", "scale");
            ident.put("path", path == null ? "/_" : path);
            ident.put("numerator", 1);
            ident.put("denominator", 1);
            ident.put("expectedMin", -1.0e6);
            ident.put("expectedMax", 1.0e6);
            ops.add(0, ident);
        }
        out.put("ops", ops);
        return out;
    }

    private static String firstPath(Map<String, Object> op) {
        if (op.containsKey("path")) return String.valueOf(op.get("path"));
        if (op.containsKey("to")) return String.valueOf(op.get("to"));
        if (op.containsKey("from")) return String.valueOf(op.get("from"));
        return null;
    }
}
