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

    public static TransformProgram none() {
        return TransformProgram.builder()
                .empty(true)
                .streamable(true)
                .renames(Map.of())
                .defaults(Map.of())
                .coercions(Map.of())
                .removals(Set.of())
                .moves(List.of())
                .build();
    }
}
