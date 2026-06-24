package com.selfhealing.analysis.service.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single typed shape produced by both the real tool-use path and the mock
 * fallback. {@code ruleType} comes straight from the chosen tool name; everything
 * else from its validated {@code input}. {@code source} records provenance so a
 * saved result can be told apart from a mock after the fact.
 */
public record AnalysisToolResult(
        Source source,
        String model,
        String ruleType,
        Map<String, Object> transformationRules,
        String rootCause,
        double confidence,
        String suggestedPermanentFix) {

    public enum Source { CLAUDE, MOCK }

    /**
     * Splits a tool {@code input} into the transformation-rule map (typed params)
     * and the narrative fields (rootCause/confidence/suggestedPermanentFix).
     */
    public static AnalysisToolResult fromToolInput(
            Source source, String model, String toolName, Map<String, Object> input) {
        String ruleType = AnalysisTools.ruleTypeForTool(toolName);
        Map<String, Object> rules = new LinkedHashMap<>();
        if (ruleType != null) rules.put("type", ruleType);

        double confidence = 0.0;
        String rootCause = null;
        String permanentFix = null;

        if (input != null) {
            for (Map.Entry<String, Object> e : input.entrySet()) {
                switch (e.getKey()) {
                    case "confidence" -> confidence = toDouble(e.getValue());
                    case "rootCause" -> rootCause = str(e.getValue());
                    case "suggestedPermanentFix" -> permanentFix = str(e.getValue());
                    default -> rules.put(e.getKey(), e.getValue());
                }
            }
        }

        return new AnalysisToolResult(source, model, ruleType, rules,
                rootCause != null ? rootCause : "Unable to determine root cause",
                confidence,
                permanentFix != null ? permanentFix : "Manual investigation required");
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? 0.0 : Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
