package com.selfhealing.analysis.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical machine-readable failure artifact. The LLM diagnosis path should
 * reason over this object (plus MCP-retrieved taxonomy/precedents), not raw
 * error strings. Additive alongside {@code errorMessage} — never a replacement.
 */
public record ErrorSignature(
        UUID failureId,
        UUID tenantId,
        String category,
        String templateId,
        String jsonPath,
        String changeType,
        String expectedType,
        String observedType,
        Object observedValue,
        String contractRef,
        Map<String, Object> contractCoords,
        Double specTrust,
        String rawExcerpt,
        Map<String, Object> reconciliation) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (failureId != null) m.put("failureId", failureId.toString());
        if (tenantId != null) m.put("tenantId", tenantId.toString());
        m.put("category", category);
        m.put("template_id", templateId);
        m.put("json_path", jsonPath);
        m.put("change_type", changeType);
        m.put("expected_type", expectedType);
        m.put("observed_type", observedType);
        m.put("observed_value", observedValue);
        m.put("contract_ref", contractRef);
        m.put("contract_coords", contractCoords);
        m.put("spec_trust", specTrust);
        m.put("raw_excerpt", rawExcerpt);
        if (reconciliation != null && !reconciliation.isEmpty()) {
            m.put("reconciliation", reconciliation);
        }
        return m;
    }

    /**
     * CEGIS structural sketch-with-holes (Phase 8.3b).
     * When {@code minimalFields} is provided (ddmin output), one typed hole per field;
     * otherwise a single hole from this signature.
     */
    public Map<String, Object> toSketchHint() {
        return toStructuralSketch(null);
    }

    public Map<String, Object> toStructuralSketch(java.util.List<Map<String, Object>> minimalFields) {
        Map<String, Object> sketch = new LinkedHashMap<>();
        sketch.put("kind", "structural_sketch_with_holes");
        java.util.List<Map<String, Object>> holes = new java.util.ArrayList<>();
        if (minimalFields != null && !minimalFields.isEmpty()) {
            for (Map<String, Object> f : minimalFields) {
                holes.add(holeFrom(
                        str(f.get("change_type")),
                        str(f.get("json_path")),
                        str(f.get("expected_type")),
                        str(f.get("observed_type"))));
            }
        } else {
            holes.add(holeFrom(changeType, jsonPath, expectedType, observedType));
        }
        sketch.put("holes", holes);
        sketch.put("change_type", changeType);
        sketch.put("json_path", jsonPath);
        sketch.put("expected_type", expectedType);
        sketch.put("observed_type", observedType);
        // Backward-compatible single hole string for CE prompts
        sketch.put("hole", holes.isEmpty() ? null : holes.get(0).get("token"));
        sketch.put("allowedOpcodes", allowedOpcodesFor(changeType));
        return sketch;
    }

    private static Map<String, Object> holeFrom(
            String changeType, String jsonPath, String expectedType, String observedType) {
        Map<String, Object> hole = new LinkedHashMap<>();
        hole.put("change_type", changeType);
        hole.put("json_path", jsonPath);
        hole.put("expected_type", expectedType);
        hole.put("observed_type", observedType);
        hole.put("token", changeType == null ? null
                : "<HOLE:" + changeType.toLowerCase()
                + (jsonPath != null ? " " + jsonPath : "") + ">");
        hole.put("allowedOpcodes", allowedOpcodesFor(changeType));
        return hole;
    }

    /** Synthesis-only path: reject ops outside this allowlist for the change type. */
    public static java.util.List<String> allowedOpcodesFor(String changeType) {
        if (changeType == null) return java.util.List.of();
        String u = changeType.toUpperCase();
        if (u.contains("RENAME")) return java.util.List.of("rename", "move", "copy");
        if (u.contains("COERCE") || u.contains("TYPE")) return java.util.List.of("coerce", "map_value", "string_op");
        if (u.contains("DEFAULT") || u.contains("ADD")) return java.util.List.of("default", "coalesce");
        if (u.contains("REMOVE")) return java.util.List.of("remove", "strip_unknown");
        if (u.contains("WRAP")) return java.util.List.of("wrap", "wrap_array");
        if (u.contains("UNWRAP")) return java.util.List.of("unwrap", "unwrap_array");
        return java.util.List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
