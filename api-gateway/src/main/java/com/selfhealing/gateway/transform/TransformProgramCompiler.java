package com.selfhealing.gateway.transform;

import com.selfhealing.gateway.model.ResponseTransformationRule;
import com.selfhealing.gateway.model.TransformationRule;
import com.selfhealing.gateway.util.DefaultValueNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TransformProgramCompiler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TransformProgramCompiler.class);

    private final MendrScriptCompiler mendrScriptCompiler;
    private final com.selfhealing.gateway.transform.dsl.MendrScriptVerifier verifier;

    @org.springframework.beans.factory.annotation.Autowired
    public TransformProgramCompiler(MendrScriptCompiler mendrScriptCompiler,
                                    com.selfhealing.gateway.transform.dsl.MendrScriptVerifier verifier) {
        this.mendrScriptCompiler = mendrScriptCompiler;
        this.verifier = verifier;
    }

    /** Convenience for tests / legacy callers that don't need a Spring-managed compiler. */
    public TransformProgramCompiler() {
        this(new MendrScriptCompiler(new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.selfhealing.gateway.transform.dsl.MendrScriptVerifier());
    }

    public TransformProgram compileRequest(List<TransformationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return TransformProgram.none();
        }
        Acc acc = new Acc();
        for (TransformationRule rule : rules) {
            if (rule.getRuleType() == TransformationRule.RuleType.NESTED_TRANSFORM) {
                acc.streamable = false;
            }
            Map<String, Object> def = rule.getRuleDefinition();
            if (def == null) {
                continue;
            }
            if (rule.getRuleType() == TransformationRule.RuleType.DSL_PROGRAM) {
                putDslProgram(acc, def);
                continue;
            }
            putRenames(acc, def.get("mappings"));
            putDefaults(acc, def.get("defaults"));
            putCoercions(acc, def.get("coercions"));
            putRemovals(acc, def.get("fields"));
            putMoves(acc, def.get("moves"));
            putScales(acc, def.get("scales"));
            putCoalesce(acc, def.get("coalesce"));
            putValueMaps(acc, def.get("valueMaps"));
            putDateFormats(acc, def.get("dateFormats"));
            putStripUnknown(acc, def.get("stripUnknown"));
            putPathList(acc, acc.wrapArraysByPath, def.get("wrapArrays"));
            putPathList(acc, acc.unwrapArraysByPath, def.get("unwrapArrays"));
        }
        return acc.build();
    }

    public TransformProgram compileResponse(List<ResponseTransformationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return TransformProgram.none();
        }
        Acc acc = new Acc();
        for (ResponseTransformationRule rule : rules) {
            Map<String, Object> def = rule.getRuleDefinition();
            if (def == null) {
                continue;
            }
            switch (rule.getRuleType()) {
                case RESPONSE_FIELD_RENAME -> putRenames(acc, def.get("mappings"));
                case RESPONSE_ADD_DEFAULT -> putDefaults(acc, def.get("defaults"));
                case RESPONSE_TYPE_COERCE -> putCoercions(acc, def.get("coercions"));
                case RESPONSE_REMOVE_FIELD -> putRemovals(acc, def.get("fields"));
                case RESPONSE_FIELD_MOVE -> putMoves(acc, def.get("moves"));
                case RESPONSE_WRAP -> {
                    acc.wrapKey = str(def.getOrDefault("key", "data"));
                    acc.streamable = false;
                }
                case RESPONSE_UNWRAP -> {
                    acc.unwrapKey = str(def.getOrDefault("key", "data"));
                    acc.streamable = false;
                }
                default -> { }
            }
        }
        return acc.build();
    }

    @SuppressWarnings("unchecked")
    private void putRenames(Acc acc, Object o) {
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    String key = k.toString();
                    String val = v.toString();
                    String prev = acc.renames.get(key);
                    if (prev != null && !prev.equals(val)) {
                        acc.conflicts.add("field '" + key + "' renamed to both '"
                                + prev + "' and '" + val + "'");
                    }
                    acc.renames.put(key, val);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void putDefaults(Acc acc, Object o) {
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null) {
                    String key = k.toString();
                    Object val = DefaultValueNormalizer.normalize(v);
                    Object prev = acc.defaults.get(key);
                    if (prev != null && !java.util.Objects.equals(prev, val)) {
                        acc.conflicts.add("field '" + key + "' defaulted to both '"
                                + prev + "' and '" + val + "'");
                    }
                    acc.defaults.put(key, val);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void putCoercions(Acc acc, Object o) {
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null) {
                    String key = k.toString();
                    String val = String.valueOf(v);
                    String prev = acc.coercions.get(key);
                    if (prev != null && !prev.equals(val)) {
                        acc.conflicts.add("field '" + key + "' coerced to both '"
                                + prev + "' and '" + val + "'");
                    }
                    acc.coercions.put(key, val);
                }
            });
        }
    }

    private void putRemovals(Acc acc, Object o) {
        if (o instanceof List<?> list) {
            list.forEach(f -> {
                if (f != null) {
                    acc.removals.add(f.toString());
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void putMoves(Acc acc, Object o) {
        if (!(o instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object from = m.get("from");
            Object to = m.get("to");
            if (from == null || to == null) {
                continue;
            }
            Map<String, Object> move = new HashMap<>();
            move.put("from", from.toString());
            move.put("to", to.toString());
            Object copy = m.get("copy");
            move.put("copy", copy instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(copy)));
            acc.moves.add(move);
        }
        if (!acc.moves.isEmpty()) {
            // Restructure across nesting => not safe for the flat streaming path.
            acc.streamable = false;
        }
    }

    /**
     * SCALE value op (§12/§13). Each entry carries an exact rational factor and a
     * mandatory [expectedMin, expectedMax] post-condition. Two scales on the same
     * path with a different factor is a genuine conflict.
     */
    private void putScales(Acc acc, Object o) {
        if (!(o instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object path = m.get("path");
            if (path == null) {
                continue;
            }
            String p = path.toString();
            Map<String, Object> scale = new HashMap<>();
            scale.put("path", p);
            scale.put("numerator", numOrNull(m.get("numerator")));
            scale.put("denominator", numOrNull(m.get("denominator")));
            scale.put("expectedMin", numOrNull(m.get("expectedMin")));
            scale.put("expectedMax", numOrNull(m.get("expectedMax")));

            Map<String, Object> prev = acc.scalesByPath.get(p);
            if (prev != null
                    && (!java.util.Objects.equals(prev.get("numerator"), scale.get("numerator"))
                        || !java.util.Objects.equals(prev.get("denominator"), scale.get("denominator")))) {
                acc.conflicts.add("field '" + p + "' scaled by two different factors");
            }
            acc.scalesByPath.put(p, scale);
        }
        if (!acc.scalesByPath.isEmpty()) {
            // value-mutating + may target nested paths => use the buffered path
            acc.streamable = false;
        }
    }

    private static Double numOrNull(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** COALESCE: each {path, value} — replacement applied only when the value at
     *  {@code path} is present-but-null. Two coalesces on one path with a different
     *  value is a conflict. */
    private void putCoalesce(Acc acc, Object o) {
        if (!(o instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || m.get("path") == null) {
                continue;
            }
            String p = m.get("path").toString();
            Object val = DefaultValueNormalizer.normalize(m.get("value"));
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", p);
            entry.put("value", val);

            Map<String, Object> prev = acc.coalesceByPath.get(p);
            if (prev != null && !java.util.Objects.equals(prev.get("value"), val)) {
                acc.conflicts.add("field '" + p + "' coalesced to two different values");
            }
            acc.coalesceByPath.put(p, entry);
        }
        if (!acc.coalesceByPath.isEmpty()) {
            // value-mutating + may target nested paths => not safe for the flat
            // streaming transformer (which cannot express null-replacement).
            acc.streamable = false;
        }
    }

    /** MAP_VALUE: each {path, mapping:{from->to}, onUnmapped}. */
    @SuppressWarnings("unchecked")
    private void putValueMaps(Acc acc, Object o) {
        if (!(o instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || m.get("path") == null) continue;
            String p = m.get("path").toString();
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", p);
            Map<String, Object> mapping = new java.util.LinkedHashMap<>();
            if (m.get("mapping") instanceof Map<?, ?> mm) {
                mm.forEach((k, v) -> {
                    if (k != null && v != null) mapping.put(k.toString(), v.toString());
                });
            }
            entry.put("mapping", mapping);
            entry.put("onUnmapped", m.get("onUnmapped") != null ? m.get("onUnmapped").toString() : "reject");

            Map<String, Object> prev = acc.valueMapsByPath.get(p);
            if (prev != null && !java.util.Objects.equals(prev.get("mapping"), mapping)) {
                acc.conflicts.add("field '" + p + "' has two different value-maps");
            }
            acc.valueMapsByPath.put(p, entry);
        }
        if (!acc.valueMapsByPath.isEmpty()) acc.streamable = false;
    }

    /** REFORMAT_DATE: each {path, sourceFormat, targetFormat}. */
    private void putDateFormats(Acc acc, Object o) {
        if (!(o instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || m.get("path") == null) continue;
            String p = m.get("path").toString();
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", p);
            entry.put("sourceFormat", str0(m.get("sourceFormat")));
            entry.put("targetFormat", str0(m.get("targetFormat")));
            entry.put("assumeTimezone", str0(m.get("assumeTimezone")));

            Map<String, Object> prev = acc.dateFormatsByPath.get(p);
            if (prev != null
                    && (!java.util.Objects.equals(prev.get("targetFormat"), entry.get("targetFormat"))
                        || !java.util.Objects.equals(prev.get("sourceFormat"), entry.get("sourceFormat"))
                        || !java.util.Objects.equals(prev.get("assumeTimezone"), entry.get("assumeTimezone")))) {
                acc.conflicts.add("field '" + p + "' reformatted with two different date conversions");
            }
            acc.dateFormatsByPath.put(p, entry);
        }
        if (!acc.dateFormatsByPath.isEmpty()) acc.streamable = false;
    }

    /** STRIP_UNKNOWN: each {path, allowed:[...]}. */
    private void putStripUnknown(Acc acc, Object o) {
        if (!(o instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String p = m.get("path") != null ? m.get("path").toString() : "/";
            List<String> allowed = new ArrayList<>();
            if (m.get("allowed") instanceof List<?> al) {
                for (Object a : al) if (a != null) allowed.add(a.toString());
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", p);
            entry.put("allowed", allowed);

            Map<String, Object> prev = acc.stripUnknownByPath.get(p);
            if (prev != null && !java.util.Objects.equals(prev.get("allowed"), allowed)) {
                acc.conflicts.add("path '" + p + "' has two different strip-unknown allow-lists");
            }
            acc.stripUnknownByPath.put(p, entry);
        }
        if (!acc.stripUnknownByPath.isEmpty()) acc.streamable = false;
    }

    /**
     * DSL_PROGRAM (snapshot v2): parse the stored AST and append its serialized ops to
     * the merged ops[]. DSL programs always run on the buffered edge path. Ops are
     * appended in rule order (rules are already id-sorted upstream for determinism).
     */
    private void putDslProgram(Acc acc, Map<String, Object> def) {
        var program = mendrScriptCompiler.parse(def);
        // Authoritative server-side re-verification at the materialization point: a
        // DSL program that does not pass the SAME verifier the chatbot used is
        // skipped (fail-safe) so an unverified/unsafe AST can never reach the edge,
        // and a single bad rule never blocks the rest of the route from healing.
        var result = verifier.verify(program);
        if (!result.valid()) {
            log.warn("Skipping DSL_PROGRAM rule — failed server-side verification: {}",
                    String.join("; ", result.errors()));
            return;
        }
        var ops = mendrScriptCompiler.toSnapshotOps(program);
        if (ops.isEmpty()) {
            return;
        }
        acc.ops.addAll(ops);
        acc.streamable = false;
    }

    /** Shared for WRAP_ARRAY / UNWRAP_ARRAY: each {path}. */
    private void putPathList(Acc acc, java.util.Map<String, Map<String, Object>> target, Object o) {
        if (!(o instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || m.get("path") == null) continue;
            String p = m.get("path").toString();
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", p);
            target.put(p, entry);
        }
        if (!target.isEmpty()) acc.streamable = false;
    }

    private static String str0(Object o) {
        return o != null ? o.toString() : "";
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "data";
    }

    private static final class Acc {
        boolean streamable = true;
        final Map<String, String> renames = new HashMap<>();
        final Map<String, Object> defaults = new HashMap<>();
        final Map<String, String> coercions = new HashMap<>();
        final Set<String> removals = new HashSet<>();
        final List<Map<String, Object>> moves = new ArrayList<>();
        // keyed by path so duplicate/conflicting scales on one path are detectable
        final java.util.LinkedHashMap<String, Map<String, Object>> scalesByPath = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, Map<String, Object>> coalesceByPath = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, Map<String, Object>> valueMapsByPath = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, Map<String, Object>> dateFormatsByPath = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, Map<String, Object>> stripUnknownByPath = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, Map<String, Object>> wrapArraysByPath = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, Map<String, Object>> unwrapArraysByPath = new java.util.LinkedHashMap<>();
        final List<Map<String, Object>> ops = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        String wrapKey;
        String unwrapKey;

        TransformProgram build() {
            detectCrossOpConflicts();
            if (!conflicts.isEmpty()) {
                throw new TransformProgramConflictException(
                        "Conflicting transform rules on route: " + String.join("; ", conflicts));
            }
            detectRenameCollisions();
            boolean empty = renames.isEmpty() && defaults.isEmpty()
                    && coercions.isEmpty() && removals.isEmpty()
                    && moves.isEmpty() && scalesByPath.isEmpty()
                    && coalesceByPath.isEmpty() && valueMapsByPath.isEmpty()
                    && dateFormatsByPath.isEmpty() && stripUnknownByPath.isEmpty()
                    && wrapArraysByPath.isEmpty() && unwrapArraysByPath.isEmpty()
                    && ops.isEmpty()
                    && wrapKey == null && unwrapKey == null;
            return TransformProgram.builder()
                    .empty(empty)
                    .streamable(streamable)
                    .schemaVersion(ops.isEmpty() ? "v1" : "v2")
                    .ops(List.copyOf(ops))
                    .renames(Map.copyOf(renames))
                    .defaults(Map.copyOf(defaults))
                    .coercions(Map.copyOf(coercions))
                    .removals(Set.copyOf(removals))
                    .moves(List.copyOf(moves))
                    .scales(List.copyOf(scalesByPath.values()))
                    .coalesce(List.copyOf(coalesceByPath.values()))
                    .valueMaps(List.copyOf(valueMapsByPath.values()))
                    .dateFormats(List.copyOf(dateFormatsByPath.values()))
                    .stripUnknown(List.copyOf(stripUnknownByPath.values()))
                    .wrapArrays(List.copyOf(wrapArraysByPath.values()))
                    .unwrapArrays(List.copyOf(unwrapArraysByPath.values()))
                    .wrapKey(wrapKey)
                    .unwrapKey(unwrapKey)
                    .build();
        }

        /**
         * Genuine cross-bucket conflicts: a field that is both removed and
         * renamed/coerced/defaulted is ambiguous (does it survive or not?).
         */
        private void detectCrossOpConflicts() {
            for (String removed : removals) {
                if (renames.containsKey(removed)) {
                    conflicts.add("field '" + removed + "' is both removed and renamed");
                }
                if (coercions.containsKey(removed)) {
                    conflicts.add("field '" + removed + "' is both removed and coerced");
                }
                if (defaults.containsKey(removed)) {
                    conflicts.add("field '" + removed + "' is both removed and defaulted");
                }
            }
            // Wrapping AND unwrapping the same path into/out of an array is ambiguous.
            for (String p : wrapArraysByPath.keySet()) {
                if (unwrapArraysByPath.containsKey(p)) {
                    conflicts.add("path '" + p + "' is both wrap-array'd and unwrap-array'd");
                }
            }
        }

        /** Rename targets that collide with defaults or other rename targets require Map fallback. */
        private void detectRenameCollisions() {
            if (renames.isEmpty()) {
                return;
            }
            Set<String> targets = new HashSet<>(renames.values());
            if (targets.size() < renames.size()) {
                streamable = false;
                return;
            }
            for (String target : targets) {
                if (defaults.containsKey(target)) {
                    streamable = false;
                    return;
                }
                if (renames.containsKey(target)) {
                    streamable = false;
                    return;
                }
            }
        }
    }
}
