package com.selfhealing.analysis.service.bandit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 4 guardrail: fixed global bandit category enum.
 * Invalid tags must never reach {@code bandit_pending_credit}.
 */
public final class BanditCategory {

    public static final List<String> ALL = List.of(
            "STRUCTURAL_MAPPING",
            "DATA_COERCION",
            "ADD_DEFAULT",
            "FIELD_REMOVE",
            "RESPONSE_MAP",
            "ROUTING",
            "CORS"
    );

    private static final Set<String> SET = Set.copyOf(ALL);

    private BanditCategory() {}

    public static boolean isValid(String category) {
        return category != null && SET.contains(category.trim().toUpperCase(Locale.ROOT));
    }

    public static String normalize(String category) {
        if (category == null || category.isBlank()) return null;
        String u = category.trim().toUpperCase(Locale.ROOT);
        return SET.contains(u) ? u : null;
    }

    /**
     * Coerce {@code raw} into the allowed arm set for this incident.
     * <ul>
     *   <li>Missing tag → abort (null)</li>
     *   <li>Invalid tag → coerce only if exactly one allowed arm; else abort</li>
     *   <li>Valid but not in sampled set → abort</li>
     * </ul>
     */
    public static String coerceOrAbort(String raw, List<String> allowedArms) {
        List<String> allowed = allowedArms == null ? List.of() : allowedArms.stream()
                .map(BanditCategory::normalize)
                .filter(c -> c != null)
                .distinct()
                .toList();

        if (raw == null || raw.isBlank()) {
            return null; // missing → abort
        }

        String norm = normalize(raw);
        if (norm != null) {
            if (allowed.isEmpty() || allowed.contains(norm)) return norm;
            return null; // valid globally but not Thompson-sampled
        }

        // Invalid invented tag: coerce only if uniquely mappable to one arm
        if (allowed.size() == 1) return allowed.get(0);
        return null;
    }

    /** Nearest category by change_type hint when LLM invents a tag. */
    public static String nearestFromChangeType(String changeType, List<String> allowedArms) {
        String mapped = BanditService.mapChangeTypeToCategory(changeType);
        return coerceOrAbort(mapped, allowedArms);
    }

    public static Map<String, Object> schemaEnumProperty() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("enum", ALL);
        p.put("description", "True REx global category tag for this program arm");
        return p;
    }
}
