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
        List<Map<String, Object>> moves,
        boolean deterministic,
        /** MendrScript ops for UNIT_SCALE / DATE_FORMAT registry hits. */
        List<Map<String, Object>> ops,
        /** Registry rule id for provenance / kill-switch denylist. */
        String registryRuleId,
        /**
         * True when this deterministic result fully covers the mismatch set
         * (skip diagnose-first). False for partial coverage.
         */
        boolean coverageComplete
) {
    public enum Kind {
        MISSING_FIELD,
        FIELD_RENAME,
        TYPE_MISMATCH,
        FIELD_MOVE,
        UNIT_SCALE,
        DATE_FORMAT,
        NONE
    }

    public static SchemaDiffResult empty() {
        return new SchemaDiffResult(Kind.NONE, "", Set.of(), Map.of(), Map.of(), Map.of(),
                List.of(), false, List.of(), null, false);
    }

    public static SchemaDiffResult missing(String summary, Set<String> missingFields,
                                           Map<String, Object> defaults) {
        return new SchemaDiffResult(Kind.MISSING_FIELD, summary, missingFields,
                Map.of(), Map.of(), defaults, List.of(), !defaults.isEmpty(),
                List.of(), null, !defaults.isEmpty());
    }

    public static SchemaDiffResult rename(String summary, Map<String, String> renameMappings) {
        return new SchemaDiffResult(Kind.FIELD_RENAME, summary, Set.of(),
                renameMappings, Map.of(), Map.of(), List.of(), true,
                List.of(), null, true);
    }

    public static SchemaDiffResult typeMismatch(String summary, Map<String, String> coercions) {
        return new SchemaDiffResult(Kind.TYPE_MISMATCH, summary, Set.of(),
                Map.of(), coercions, Map.of(), List.of(), true,
                List.of(), null, true);
    }

    public static SchemaDiffResult move(String summary, List<Map<String, Object>> moves) {
        return new SchemaDiffResult(Kind.FIELD_MOVE, summary, Set.of(),
                Map.of(), Map.of(), Map.of(), moves, true,
                List.of(), null, true);
    }

    public static SchemaDiffResult unitScale(String summary, List<Map<String, Object>> ops,
                                             String registryRuleId, boolean coverageComplete) {
        return new SchemaDiffResult(Kind.UNIT_SCALE, summary, Set.of(),
                Map.of(), Map.of(), Map.of(), List.of(), true,
                ops == null ? List.of() : List.copyOf(ops), registryRuleId, coverageComplete);
    }

    public static SchemaDiffResult dateFormat(String summary, List<Map<String, Object>> ops,
                                              String registryRuleId, boolean coverageComplete) {
        return new SchemaDiffResult(Kind.DATE_FORMAT, summary, Set.of(),
                Map.of(), Map.of(), Map.of(), List.of(), true,
                ops == null ? List.of() : List.copyOf(ops), registryRuleId, coverageComplete);
    }

    public boolean hasDeterministicRule() {
        if (!deterministic || kind == Kind.NONE) return false;
        if (kind == Kind.MISSING_FIELD && suggestedDefaults.isEmpty()) return false;
        if (kind == Kind.FIELD_RENAME && renameMappings.isEmpty()) return false;
        if (kind == Kind.TYPE_MISMATCH && typeCoercions.isEmpty()) return false;
        if (kind == Kind.FIELD_MOVE && (moves == null || moves.isEmpty())) return false;
        if ((kind == Kind.UNIT_SCALE || kind == Kind.DATE_FORMAT)
                && (ops == null || ops.isEmpty())) return false;
        return true;
    }

    public boolean isRegistryDeterministic() {
        return kind == Kind.UNIT_SCALE || kind == Kind.DATE_FORMAT;
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
            case FIELD_MOVE -> {
                rules.put("type", "FIELD_MOVE");
                rules.put("moves", moves == null ? List.of() : List.copyOf(moves));
            }
            case UNIT_SCALE, DATE_FORMAT -> {
                rules.put("type", "DSL_PROGRAM");
                rules.put("schemaVersion", "mendrscript/v1");
                rules.put("ops", ops == null ? List.of() : List.copyOf(ops));
                Map<String, Object> provenance = new LinkedHashMap<>();
                provenance.put("source", "DETERMINISTIC_REGISTRY");
                provenance.put("kind", kind.name());
                provenance.put("registryRuleId", registryRuleId);
                provenance.put("s1Source", "BY_CONSTRUCTION");
                rules.put("_provenance", provenance);
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
        sb.append("(3) same field count + type mismatch → TYPE_COERCE, ");
        sb.append("(4) unit/date registry → scale / reformat_date.\n");
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
        if (ops != null && !ops.isEmpty()) {
            sb.append("Registry MendrScript ops: ").append(ops).append("\n");
        }

        sb.append("You MUST propose exactly ONE rule type matching the primary classification above.\n");
        sb.append("Do NOT suggest ADD_DEFAULT when actual and receiver have the same number of fields.\n");
        sb.append("Do NOT suggest FIELD_RENAME when actual has fewer fields than the receiver contract.\n");
        sb.append("For ADD_DEFAULT, defaults must use JSON numbers (not strings) and amount > 0.\n\n");
    }
}
