package com.selfhealing.analysis.service;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies declared-vs-observed schema divergence by SHAPE before anything
 * downstream consumes it:
 * <ul>
 *   <li>{@link DivergenceKind#MISSING_DECLARED} — bug shape; may auto-heal</li>
 *   <li>{@link DivergenceKind#UNDECLARED_APPEARED} — security shape; never auto-heals</li>
 * </ul>
 * Applies to both request (injection / mass-assignment) and response (data exposure).
 */
@Slf4j
@Component
public class ContractReconciliationAnalyzer {

    public enum DivergenceKind {
        MISSING_DECLARED,
        UNDECLARED_APPEARED
    }

    public enum Side {
        REQUEST,
        RESPONSE
    }

    @Value
    @Builder
    public static class Divergence {
        DivergenceKind kind;
        Side side;
        String path;
        String detail;
        boolean autoHealEligible;
    }

    @Value
    @Builder
    public static class Result {
        List<Divergence> divergences;
        int missingDeclaredCount;
        int undeclaredAppearedCount;

        public boolean hasSecurityFindings() {
            return undeclaredAppearedCount > 0;
        }
    }

    /**
     * Diff a declared schema (OpenAPI / inferred) against an observed payload map.
     * Top-level and one level of nested properties are compared.
     */
    @SuppressWarnings("unchecked")
    public Result analyze(Map<String, Object> declaredSchema,
                          Map<String, Object> observedPayload,
                          Side side) {
        List<Divergence> out = new ArrayList<>();
        if (declaredSchema == null || declaredSchema.isEmpty()) {
            return Result.builder()
                    .divergences(out)
                    .missingDeclaredCount(0)
                    .undeclaredAppearedCount(0)
                    .build();
        }
        if (observedPayload == null) {
            observedPayload = Map.of();
        }

        Set<String> declaredFields = declaredFieldNames(declaredSchema);
        Set<String> required = requiredFieldNames(declaredSchema);
        boolean additionalAllowed = Boolean.TRUE.equals(declaredSchema.get("additionalProperties"))
                || declaredSchema.get("additionalProperties") instanceof Map;

        // MISSING_DECLARED: required (or declared) field absent / wrong type in traffic
        for (String field : declaredFields) {
            if (!observedPayload.containsKey(field)) {
                if (required.contains(field)) {
                    out.add(Divergence.builder()
                            .kind(DivergenceKind.MISSING_DECLARED)
                            .side(side)
                            .path("/" + field)
                            .detail("declared required field absent in observed payload")
                            .autoHealEligible(true)
                            .build());
                }
                continue;
            }
            Object expectedType = expectedType(declaredSchema, field);
            Object actual = observedPayload.get(field);
            if (expectedType != null && actual != null && !typeMatches(expectedType, actual)) {
                out.add(Divergence.builder()
                        .kind(DivergenceKind.MISSING_DECLARED)
                        .side(side)
                        .path("/" + field)
                        .detail("declared type " + expectedType + " but observed "
                                + actual.getClass().getSimpleName())
                        .autoHealEligible(true)
                        .build());
            }
        }

        // UNDECLARED_APPEARED: traffic has surface the spec never mentions
        if (!additionalAllowed) {
            for (String field : observedPayload.keySet()) {
                if (!declaredFields.contains(field)) {
                    out.add(Divergence.builder()
                            .kind(DivergenceKind.UNDECLARED_APPEARED)
                            .side(side)
                            .path("/" + field)
                            .detail(side == Side.REQUEST
                                    ? "undeclared request field (injection / mass-assignment risk)"
                                    : "undeclared response field (excessive data exposure risk)")
                            .autoHealEligible(false)
                            .build());
                }
            }
        }

        int missing = 0;
        int undeclared = 0;
        for (Divergence d : out) {
            if (d.getKind() == DivergenceKind.MISSING_DECLARED) missing++;
            else undeclared++;
        }

        if (undeclared > 0) {
            log.info("ContractReconciliation: {} UNDECLARED_APPEARED on {} (never auto-heal)",
                    undeclared, side);
        }

        return Result.builder()
                .divergences(out)
                .missingDeclaredCount(missing)
                .undeclaredAppearedCount(undeclared)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Topology drift: the SAME declared-vs-observed shape logic, lifted from a
    // single payload to the whole dependency graph. Reuses the MISSING_DECLARED /
    // UNDECLARED_APPEARED framing at edge granularity:
    //   OBSERVED_UNDECLARED  — traffic edge with no declared counterpart = a SHADOW
    //                          dependency (security shape; never auto-heal).
    //   DECLARED_UNOBSERVED  — declared edge never seen in traffic = a possibly-stale
    //                          / dead dependency (informational).
    // Pure set logic over caller-supplied edge sets; the DB read (RLS-scoped) lives
    // in TopologyQueryService so this class stays side-effect-free and unit-testable.
    // ─────────────────────────────────────────────────────────────────────────

    public enum TopologyDriftKind {
        OBSERVED_UNDECLARED,
        DECLARED_UNOBSERVED
    }

    @Value
    @Builder
    public static class TopologyEdge {
        /** Canonical comparison key (e.g. {@code source>target}). Drives set membership. */
        String key;
        String sourceService;
        String targetService;
        String endpoint;
    }

    @Value
    @Builder
    public static class EdgeDrift {
        TopologyDriftKind kind;
        String edgeKey;
        String sourceService;
        String targetService;
        String detail;
        boolean securityRelevant;
    }

    @Value
    @Builder
    public static class TopologyDriftResult {
        List<EdgeDrift> drifts;
        int observedUndeclaredCount;
        int declaredUnobservedCount;

        public boolean hasSecurityFindings() {
            return observedUndeclaredCount > 0;
        }
    }

    /**
     * Diff declared edges (manifest / OpenAPI) against observed edges (edge traffic).
     * Comparison is by each {@link TopologyEdge#getKey()} — the caller chooses granularity
     * (service-pair {@code source>target} is the robust default; endpoint templates are
     * noisier to match against concrete observed paths).
     */
    public TopologyDriftResult analyzeTopology(Collection<TopologyEdge> declared,
                                               Collection<TopologyEdge> observed) {
        Map<String, TopologyEdge> declaredByKey = indexByKey(declared);
        Map<String, TopologyEdge> observedByKey = indexByKey(observed);

        List<EdgeDrift> out = new ArrayList<>();
        int observedUndeclared = 0;
        int declaredUnobserved = 0;

        for (Map.Entry<String, TopologyEdge> e : observedByKey.entrySet()) {
            if (!declaredByKey.containsKey(e.getKey())) {
                TopologyEdge edge = e.getValue();
                out.add(EdgeDrift.builder()
                        .kind(TopologyDriftKind.OBSERVED_UNDECLARED)
                        .edgeKey(e.getKey())
                        .sourceService(edge.getSourceService())
                        .targetService(edge.getTargetService())
                        .detail("traffic observed with no declared edge (shadow dependency)")
                        .securityRelevant(true)
                        .build());
                observedUndeclared++;
            }
        }

        for (Map.Entry<String, TopologyEdge> e : declaredByKey.entrySet()) {
            if (!observedByKey.containsKey(e.getKey())) {
                TopologyEdge edge = e.getValue();
                out.add(EdgeDrift.builder()
                        .kind(TopologyDriftKind.DECLARED_UNOBSERVED)
                        .edgeKey(e.getKey())
                        .sourceService(edge.getSourceService())
                        .targetService(edge.getTargetService())
                        .detail("declared edge never observed in traffic (possibly stale / dead dependency)")
                        .securityRelevant(false)
                        .build());
                declaredUnobserved++;
            }
        }

        if (observedUndeclared > 0) {
            log.info("ContractReconciliation: {} OBSERVED_UNDECLARED topology edge(s) — shadow dependency",
                    observedUndeclared);
        }

        return TopologyDriftResult.builder()
                .drifts(out)
                .observedUndeclaredCount(observedUndeclared)
                .declaredUnobservedCount(declaredUnobserved)
                .build();
    }

    private static Map<String, TopologyEdge> indexByKey(Collection<TopologyEdge> edges) {
        Map<String, TopologyEdge> out = new LinkedHashMap<>();
        if (edges != null) {
            for (TopologyEdge e : edges) {
                if (e != null && e.getKey() != null && !out.containsKey(e.getKey())) {
                    out.put(e.getKey(), e);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> declaredFieldNames(Map<String, Object> schema) {
        Set<String> out = new LinkedHashSet<>();
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> map) {
            for (Object k : map.keySet()) out.add(String.valueOf(k));
            return out;
        }
        for (String k : schema.keySet()) {
            if (!Set.of("type", "required", "properties", "items", "enum", "format",
                    "additionalProperties", "oneOf", "allOf", "anyOf", "$ref", "description")
                    .contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> requiredFieldNames(Map<String, Object> schema) {
        Object req = schema.get("required");
        Set<String> out = new LinkedHashSet<>();
        if (req instanceof List<?> list) {
            for (Object o : list) out.add(String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object expectedType(Map<String, Object> schema, String field) {
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> map) {
            Object child = map.get(field);
            if (child instanceof Map<?, ?> cm) {
                return cm.get("type");
            }
        }
        Object flat = schema.get(field);
        if (flat instanceof Map<?, ?> fm) return fm.get("type");
        if (flat instanceof String s) return s;
        return null;
    }

    private static boolean typeMatches(Object expectedType, Object actual) {
        String t = String.valueOf(expectedType).toLowerCase();
        return switch (t) {
            case "string" -> actual instanceof String;
            case "integer" -> actual instanceof Number && !(actual instanceof Double || actual instanceof Float);
            case "number" -> actual instanceof Number;
            case "boolean" -> actual instanceof Boolean;
            case "array" -> actual instanceof List;
            case "object" -> actual instanceof Map;
            default -> true;
        };
    }

    /** Convenience: summarize for metrics / UI. */
    public Map<String, Object> toMetricMap(Result result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("missingDeclared", result.getMissingDeclaredCount());
        m.put("undeclaredAppeared", result.getUndeclaredAppearedCount());
        m.put("securityRelevant", result.hasSecurityFindings());
        m.put("autoHealEligible", result.getDivergences().stream()
                .filter(Divergence::isAutoHealEligible).count());
        return m;
    }
}
