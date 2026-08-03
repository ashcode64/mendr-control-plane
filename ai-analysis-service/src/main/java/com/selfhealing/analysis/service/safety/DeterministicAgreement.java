package com.selfhealing.analysis.service.safety;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Graded s₂: agreement between the proposal and independent deterministic analyzers.
 * Combines type agreement with path/op Jaccard overlap — never a confidence floor.
 */
public final class DeterministicAgreement {

    private DeterministicAgreement() {}

    public static double score(
            String aiType,
            String expectedType,
            boolean hasDeterministic,
            boolean deployable) {
        return score(aiType, expectedType, hasDeterministic, deployable, Set.of(), Set.of());
    }

    /**
     * @param aiPaths        paths/pointers referenced by the proposal
     * @param expectedPaths  paths/pointers from the deterministic analyzer rule
     */
    public static double score(
            String aiType,
            String expectedType,
            boolean hasDeterministic,
            boolean deployable,
            Collection<String> aiPaths,
            Collection<String> expectedPaths) {
        if (!deployable) return 0.20;
        String ai = norm(aiType);
        String exp = norm(expectedType);
        if (!hasDeterministic) {
            return 0.60;
        }

        double typeScore;
        if (!exp.isBlank() && exp.equals(ai)) {
            typeScore = 0.95;
        } else if ("DSL_PROGRAM".equals(ai)) {
            // DSL is an intentional alternative — do not grant 0.90 blind; require path overlap.
            typeScore = 0.55;
        } else if (!exp.isBlank()) {
            typeScore = 0.30;
        } else {
            typeScore = 0.60;
        }

        double pathScore = pathOverlap(aiPaths, expectedPaths);
        if (expectedPaths == null || expectedPaths.isEmpty()) {
            // No path oracle — type score alone (DSL stays 0.55 unless type matches).
            return clamp01(typeScore);
        }
        // Blend: path agreement is the stronger signal when available.
        return clamp01(0.45 * typeScore + 0.55 * pathScore);
    }

    public static double scoreFromRules(
            Map<String, Object> aiRules,
            Map<String, Object> deterministicRules,
            boolean hasDeterministic,
            boolean deployable) {
        String aiType = aiRules == null ? "" : String.valueOf(aiRules.getOrDefault("type", ""));
        String expectedType = deterministicRules == null
                ? ""
                : String.valueOf(deterministicRules.getOrDefault("type", ""));
        Set<String> aiPaths = extractPaths(aiRules);
        Set<String> expPaths = extractPaths(deterministicRules);
        return score(aiType, expectedType, hasDeterministic, deployable, aiPaths, expPaths);
    }

    /** @deprecated prefer {@link #scoreFromRules(Map, Map, boolean, boolean)} */
    @Deprecated
    public static double scoreFromRules(
            Map<String, Object> rules,
            String expectedType,
            boolean hasDeterministic,
            boolean deployable) {
        String aiType = rules == null ? "" : String.valueOf(rules.getOrDefault("type", ""));
        return score(aiType, expectedType, hasDeterministic, deployable,
                extractPaths(rules), Set.of());
    }

    static double pathOverlap(Collection<String> aiPaths, Collection<String> expectedPaths) {
        Set<String> a = normalizePaths(aiPaths);
        Set<String> e = normalizePaths(expectedPaths);
        if (e.isEmpty()) return 0.5;
        if (a.isEmpty()) return 0.25;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(e);
        Set<String> union = new HashSet<>(a);
        union.addAll(e);
        if (union.isEmpty()) return 0.5;
        return (double) inter.size() / union.size();
    }

    @SuppressWarnings("unchecked")
    public static Set<String> extractPaths(Map<String, Object> rules) {
        Set<String> paths = new HashSet<>();
        if (rules == null) return paths;
        collectPaths(rules.get("mappings"), paths);
        collectPaths(rules.get("renames"), paths);
        collectPaths(rules.get("defaults"), paths);
        collectPaths(rules.get("coercions"), paths);
        collectPaths(rules.get("removals"), paths);
        if (rules.get("moves") instanceof List<?> moves) {
            for (Object m : moves) {
                if (m instanceof Map<?, ?> mm) {
                    if (mm.get("from") != null) paths.add(mm.get("from").toString());
                    if (mm.get("to") != null) paths.add(mm.get("to").toString());
                    if (mm.get("path") != null) paths.add(mm.get("path").toString());
                }
            }
        }
        if (rules.get("ops") instanceof List<?> ops) {
            for (Object op : ops) {
                if (op instanceof Map<?, ?> om) {
                    for (String k : List.of("path", "from", "to", "target", "source", "jsonPath", "json_path")) {
                        if (om.get(k) != null) paths.add(om.get(k).toString());
                    }
                    if (om.get("args") instanceof Map<?, ?> args) {
                        for (String k : List.of("path", "from", "to", "target", "source")) {
                            if (args.get(k) != null) paths.add(args.get(k).toString());
                        }
                    }
                }
            }
        }
        if (rules.get("json_path") != null) paths.add(rules.get("json_path").toString());
        if (rules.get("jsonPath") != null) paths.add(rules.get("jsonPath").toString());
        return paths;
    }

    private static void collectPaths(Object node, Set<String> paths) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null) paths.add(e.getKey().toString());
                if (e.getValue() instanceof String s && s.startsWith("/")) paths.add(s);
            }
        } else if (node instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) paths.add(s);
                else if (o instanceof Map<?, ?> m) {
                    if (m.get("path") != null) paths.add(m.get("path").toString());
                    if (m.get("from") != null) paths.add(m.get("from").toString());
                    if (m.get("to") != null) paths.add(m.get("to").toString());
                }
            }
        }
    }

    private static Set<String> normalizePaths(Collection<String> paths) {
        Set<String> out = new HashSet<>();
        if (paths == null) return out;
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            String n = p.trim();
            if (!n.startsWith("/") && n.contains(".")) {
                n = "/" + n.replace('.', '/');
            }
            out.add(n);
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
