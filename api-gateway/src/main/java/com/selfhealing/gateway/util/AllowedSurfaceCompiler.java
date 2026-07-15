package com.selfhealing.gateway.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles an OpenAPI / JSON-Schema-ish map into a compact allowed-surface
 * descriptor for edge {@code x-mendr-enforce: strict} checks.
 */
public final class AllowedSurfaceCompiler {

    private AllowedSurfaceCompiler() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compile(Map<String, Object> schema,
                                              List<String> queryParamNames,
                                              String schemaSource,
                                              Double specTrust) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaSource", schemaSource == null ? "EXAMPLE_INFERRED" : schemaSource);
        out.put("specTrust", specTrust == null ? 0.5 : specTrust);

        Set<String> bodyPointers = new LinkedHashSet<>();
        boolean additionalProperties = false;
        if (schema != null && !schema.isEmpty()) {
            collectPointers("", schema, bodyPointers);
            Object ap = schema.get("additionalProperties");
            if (Boolean.TRUE.equals(ap) || (ap instanceof Map)) {
                additionalProperties = true;
            }
        }
        out.put("bodyPointers", new ArrayList<>(bodyPointers));
        out.put("additionalProperties", additionalProperties);
        out.put("queryParams", queryParamNames == null ? List.of() : List.copyOf(queryParamNames));
        out.put("additionalQueryParams", false);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectPointers(String prefix, Map<String, Object> schema, Set<String> out) {
        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map<?, ?> props)) {
            // Flat inferred schema: treat top-level keys (except meta) as fields
            for (String key : schema.keySet()) {
                if (isMeta(key)) continue;
                out.add(prefix.isEmpty() ? "/" + key : prefix + "/" + key);
            }
            return;
        }
        for (Map.Entry<?, ?> e : props.entrySet()) {
            String name = String.valueOf(e.getKey());
            String ptr = prefix.isEmpty() ? "/" + name : prefix + "/" + name;
            out.add(ptr);
            if (e.getValue() instanceof Map<?, ?> child) {
                collectPointers(ptr, (Map<String, Object>) child, out);
            }
        }
    }

    private static boolean isMeta(String key) {
        return Set.of("type", "required", "properties", "items", "enum", "format",
                "additionalProperties", "oneOf", "allOf", "anyOf", "$ref", "description")
                .contains(key);
    }
}
