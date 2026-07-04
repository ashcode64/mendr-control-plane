package com.selfhealing.gateway.transform;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pre-compiled, hot-path-ready representation of a route's transform rules. */
@Value
@Builder
public class TransformProgram {

    boolean empty;
    boolean streamable;
    /** Snapshot schema version: {@code "v1"} (legacy buckets) or {@code "v2"} (also {@link #ops}). */
    String schemaVersion;
    /**
     * MendrScript AST ops (snapshot v2). Each entry is the plain-JSON serialization
     * of a {@link com.selfhealing.gateway.transform.dsl.Op} — the closed-vocabulary
     * DSL the verifier checks and both runtimes execute. Present alongside the legacy
     * buckets so v1 edges keep working.
     */
    List<Map<String, Object>> ops;
    Map<String, String> renames;
    Map<String, Object> defaults;
    Map<String, String> coercions;
    Set<String> removals;
    String wrapKey;
    String unwrapKey;
    /**
     * Field restructure ops (FIELD_MOVE). Each entry is {from, to, copy?} with
     * JSON-Pointer paths, relocating a value across nesting levels. Applied before
     * the flat ops at the edge.
     */
    List<Map<String, Object>> moves;
    /**
     * Value-mutating scale ops (SCALE — plan §12/§13). Each entry is
     * {path, numerator, denominator, expectedMin, expectedMax}: multiply the
     * numeric value at {@code path} by the exact rational {@code numerator/
     * denominator}. {@code expectedMin/Max} is a mandatory post-condition the edge
     * asserts after applying (fail-closed on violation) — this is how a wrong
     * scale factor, which produces no parse error, is turned into a loud signal.
     */
    List<Map<String, Object>> scales;
    /** COALESCE (§12, scenario 2): each {path, value} — replace the value at
     *  {@code path} ONLY when it is present-but-null (distinct from ADD_DEFAULT,
     *  which fills an absent key). Path-based (like the other value ops) so it can
     *  target nested fields. */
    List<Map<String, Object>> coalesce;
    /** MAP_VALUE (§12, scenario 6): closed lookup-table substitution. Each entry is
     *  {path, mapping:{from->to}, onUnmapped}. The edge never guesses an unmapped value. */
    List<Map<String, Object>> valueMaps;
    /** REFORMAT_DATE (§12/§13, scenario 7): {path, sourceFormat, targetFormat} with
     *  explicit named formats and strict parse (naturally idempotent, fail-closed). */
    List<Map<String, Object>> dateFormats;
    /** STRIP_UNKNOWN (§12, scenario 5): {path, allowed:[...]} — remove keys not on the allow-list. */
    List<Map<String, Object>> stripUnknown;
    /** WRAP_ARRAY (§12, scenario 11): {path} — wrap a value into a single-element array. */
    List<Map<String, Object>> wrapArrays;
    /** UNWRAP_ARRAY (§12, scenario 11): {path} — replace a single-element array with its element. */
    List<Map<String, Object>> unwrapArrays;

    public static TransformProgram none() {
        return TransformProgram.builder()
                .empty(true)
                .streamable(true)
                .schemaVersion("v1")
                .ops(List.of())
                .renames(Map.of())
                .defaults(Map.of())
                .coercions(Map.of())
                .removals(Set.of())
                .moves(List.of())
                .scales(List.of())
                .coalesce(List.of())
                .valueMaps(List.of())
                .dateFormats(List.of())
                .stripUnknown(List.of())
                .wrapArrays(List.of())
                .unwrapArrays(List.of())
                .build();
    }
}
