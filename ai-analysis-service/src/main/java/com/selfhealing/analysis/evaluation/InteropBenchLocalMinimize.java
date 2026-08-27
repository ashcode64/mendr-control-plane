package com.selfhealing.analysis.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local minimize for InteropBench EqSat Δ when the gateway minimizer is not in-process.
 * Drops identity {@code scale} (1/1) and consecutive duplicate ops — the same class of
 * shrink the Rust eqsat layer applies for redundant arithmetic.
 */
public final class InteropBenchLocalMinimize {

    private InteropBenchLocalMinimize() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> minimize(Map<String, Object> program) {
        if (program == null || !(program.get("ops") instanceof List<?> ops)) {
            return program;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        Map<String, Object> prev = null;
        for (Object o : ops) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> op = new LinkedHashMap<>((Map<String, Object>) raw);
            if (isIdentityScale(op)) continue;
            if (prev != null && opEquals(prev, op)) continue;
            kept.add(op);
            prev = op;
        }
        Map<String, Object> out = new LinkedHashMap<>(program);
        out.put("ops", kept);
        return out;
    }

    public static double delta(Map<String, Object> before, Map<String, Object> after) {
        int b = opCount(before);
        int a = opCount(after);
        if (b == 0) return 0;
        return (double) (b - a) / b;
    }

    private static int opCount(Map<String, Object> program) {
        if (program == null) return 0;
        Object ops = program.get("ops");
        return ops instanceof List<?> list ? list.size() : 0;
    }

    private static boolean isIdentityScale(Map<String, Object> op) {
        if (!"scale".equals(String.valueOf(op.get("op")))) return false;
        double n = toD(op.get("numerator"));
        double d = toD(op.get("denominator"));
        return Math.abs(n - 1.0) < 1e-12 && Math.abs(d - 1.0) < 1e-12;
    }

    private static boolean opEquals(Map<String, Object> a, Map<String, Object> b) {
        return a.equals(b);
    }

    private static double toD(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? Double.NaN : Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
