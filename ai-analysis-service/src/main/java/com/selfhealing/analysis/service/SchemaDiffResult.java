package com.selfhealing.analysis.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured result of comparing actual request vs registered contracts.
 */
public record SchemaDiffResult(
        Kind kind,
        String summary,
        Set<String> missingFields,
        Map<String, String> renameMappings,
        Map<String, String> typeCoercions,
        Map<String, Object> suggestedDefaults,
        boolean deterministic
) {
    public enum Kind {
        MISSING_FIELD,
        FIELD_RENAME,
        TYPE_MISMATCH,
        NONE
    }

    public static SchemaDiffResult empty() {
        return new SchemaDiffResult(Kind.NONE, "", Set.of(), Map.of(), Map.of(), Map.of(), false);
    }

    public boolean hasDeterministicRule() {
        if (!deterministic || kind == Kind.NONE) return false;
        if (kind == Kind.MISSING_FIELD && suggestedDefaults.isEmpty()) return false;
        if (kind == Kind.FIELD_RENAME && renameMappings.isEmpty()) return false;
        if (kind == Kind.TYPE_MISMATCH && typeCoercions.isEmpty()) return false;
        return true;
    }

    /** Single rule object aligned with gateway/rule-engine expectations. */
    public Map<String, Object> toTransformationRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        switch (kind) {
            case MISSING_FIELD -> {
                rules.put("type", "ADD_DEFAULT");
                rules.put("defaults", new LinkedHashMap<>(suggestedDefaults));
            }
            case FIELD_RENAME -> {
                rules.put("type", "FIELD_RENAME");
                rules.put("mappings", new LinkedHashMap<>(renameMappings));
            }
            case TYPE_MISMATCH -> {
                rules.put("type", "TYPE_COERCE");
                rules.put("coercions", new LinkedHashMap<>(typeCoercions));
            }
            default -> { }
        }
        return rules;
    }

    public void appendToPrompt(StringBuilder sb) {
        if (kind == Kind.NONE) return;

        sb.append("=== STRUCTURED SCHEMA DIFF (authoritative — apply this priority) ===\n");
        sb.append("Compare actual vs receiver contract FIELD COUNTS first.\n");
        sb.append("Priority order: (1) actual has FEWER fields than receiver → ADD_DEFAULT, ");
        sb.append("(2) same field count + name mismatch → FIELD_RENAME, ");
        sb.append("(3) same field count + type mismatch → TYPE_COERCE.\n");
        sb.append("Primary classification: ").append(kind).append("\n");
        sb.append("Summary: ").append(summary).append("\n");

        if (!missingFields.isEmpty()) {
            sb.append("Missing fields: ").append(missingFields).append("\n");
            sb.append("Suggested defaults: ").append(suggestedDefaults).append("\n");
        }
        if (!renameMappings.isEmpty()) {
            sb.append("Rename mappings: ").append(renameMappings).append("\n");
        }
        if (!typeCoercions.isEmpty()) {
            sb.append("Type coercions: ").append(typeCoercions).append("\n");
        }

        sb.append("You MUST propose exactly ONE rule type matching the primary classification above.\n");
        sb.append("Do NOT suggest ADD_DEFAULT when actual and receiver have the same number of fields.\n");
        sb.append("Do NOT suggest FIELD_RENAME when actual has fewer fields than the receiver contract.\n");
        sb.append("For ADD_DEFAULT, defaults must use JSON numbers (not strings) and amount > 0.\n\n");
    }
}
