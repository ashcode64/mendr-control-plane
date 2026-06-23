package com.selfhealing.analysis.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Structured result of comparing actual response vs caller expected contract.
 * Reports all detected issues; primary kind selects one rule for deployment.
 */
public record ResponseDiffResult(
        Kind primaryKind,
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

    public static ResponseDiffResult empty() {
        return new ResponseDiffResult(
                Kind.NONE, "", Set.of(), Map.of(), Map.of(), Map.of(), false);
    }

    public boolean hasDeterministicRule() {
        return deterministic && primaryKind != Kind.NONE;
    }

    public boolean hasAnyIssues() {
        return !missingFields.isEmpty() || !renameMappings.isEmpty() || !typeCoercions.isEmpty();
    }

    /** Single response rule aligned with rule-engine expectations. */
    public Map<String, Object> toTransformationRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        switch (primaryKind) {
            case MISSING_FIELD -> {
                rules.put("type", "RESPONSE_ADD_DEFAULT");
                rules.put("defaults", new LinkedHashMap<>(suggestedDefaults));
            }
            case FIELD_RENAME -> {
                rules.put("type", "RESPONSE_FIELD_RENAME");
                rules.put("mappings", new LinkedHashMap<>(renameMappings));
            }
            case TYPE_MISMATCH -> {
                rules.put("type", "RESPONSE_TYPE_COERCE");
                rules.put("coercions", new LinkedHashMap<>(typeCoercions));
            }
            default -> { }
        }
        return rules;
    }

    public void appendToPrompt(StringBuilder sb) {
        if (primaryKind == Kind.NONE && !hasAnyIssues()) return;

        sb.append("=== STRUCTURED RESPONSE DIFF (authoritative — apply this priority) ===\n");
        sb.append("Caller expected response vs actual received compared.\n");
        sb.append("Priority order: (1) missing fields → RESPONSE_ADD_DEFAULT, ");
        sb.append("(2) name mismatches → RESPONSE_FIELD_RENAME, ");
        sb.append("(3) type mismatches → RESPONSE_TYPE_COERCE.\n");
        sb.append("Primary classification (deploy ONE rule now): ").append(primaryKind).append("\n");
        sb.append("Summary: ").append(summary).append("\n");

        if (!missingFields.isEmpty()) {
            sb.append("Missing response fields: ").append(missingFields).append("\n");
            sb.append("Suggested defaults: ").append(suggestedDefaults).append("\n");
        }
        if (!renameMappings.isEmpty()) {
            sb.append("Rename mappings: ").append(renameMappings).append("\n");
        }
        if (!typeCoercions.isEmpty()) {
            sb.append("Type coercions: ").append(typeCoercions).append("\n");
        }

        sb.append("You MUST propose exactly ONE rule type matching the primary classification.\n");
        sb.append("Remaining issues will be fixed on subsequent retries after this rule is approved.\n");
        sb.append("For RESPONSE_ADD_DEFAULT, defaults must use JSON numbers (not strings) where numeric.\n\n");
    }
}
