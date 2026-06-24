package com.selfhealing.gateway.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers a lightweight JSON-Schema-ish descriptor from one or more example payloads.
 *
 * <p>A single example can only say "this field exists and looked like X". Given
 * several examples it additionally expresses which fields are REQUIRED (present in
 * every example) vs optional, the type per field, and a small enum of observed
 * values for low-cardinality string fields — exactly what a deterministic analyzer
 * needs to tell "required vs optional" rather than "differs from the one example".
 *
 * <p>Output shape (intentionally simple, not full JSON Schema):
 * <pre>
 * {
 *   "type": "object",
 *   "required": ["customerId", "amount"],
 *   "properties": {
 *     "customerId": {"type": "string"},
 *     "amount":     {"type": "number"},
 *     "status":     {"type": "string", "enum": ["PAID", "PENDING"]},
 *     "items":      {"type": "array", "items": {"type": "object"}}
 *   }
 * }
 * </pre>
 */
public final class JsonSchemaInferrer {

    private static final int MAX_ENUM_VALUES = 8;

    private JsonSchemaInferrer() {}

    public static Map<String, Object> infer(List<Map<String, Object>> examples) {
        List<Map<String, Object>> nonNull = new ArrayList<>();
        if (examples != null) {
            for (Map<String, Object> e : examples) {
                if (e != null && !e.isEmpty()) nonNull.add(e);
            }
        }
        if (nonNull.isEmpty()) return null;

        Set<String> allKeys = new LinkedHashSet<>();
        for (Map<String, Object> e : nonNull) allKeys.addAll(e.keySet());

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (String key : allKeys) {
            int presentCount = 0;
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> e : nonNull) {
                if (e.containsKey(key)) {
                    presentCount++;
                    values.add(e.get(key));
                }
            }
            properties.put(key, describe(values));
            if (presentCount == nonNull.size()) {
                required.add(key);
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", required);
        schema.put("properties", properties);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> describe(List<Object> values) {
        Map<String, Object> desc = new LinkedHashMap<>();
        String type = "null";
        boolean enumEligible = true;
        Set<String> observed = new LinkedHashSet<>();

        for (Object v : values) {
            String t = jsonType(v);
            if (!"null".equals(t)) type = t;
            if ("string".equals(t)) {
                observed.add(v.toString());
            } else {
                enumEligible = false;
            }
        }

        desc.put("type", type);

        if ("array".equals(type)) {
            desc.put("items", arrayItemSchema(values));
        }
        if ("object".equals(type)) {
            List<Map<String, Object>> nested = new ArrayList<>();
            for (Object v : values) {
                if (v instanceof Map<?, ?> m) nested.add((Map<String, Object>) m);
            }
            Map<String, Object> nestedSchema = infer(nested);
            if (nestedSchema != null) {
                desc.put("required", nestedSchema.get("required"));
                desc.put("properties", nestedSchema.get("properties"));
            }
        }
        if (enumEligible && !observed.isEmpty() && observed.size() <= MAX_ENUM_VALUES) {
            desc.put("enum", new ArrayList<>(observed));
        }
        return desc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arrayItemSchema(List<Object> values) {
        List<Map<String, Object>> elementObjects = new ArrayList<>();
        String elementType = "object";
        for (Object v : values) {
            if (v instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        elementObjects.add((Map<String, Object>) m);
                    } else if (item != null) {
                        elementType = jsonType(item);
                    }
                }
            }
        }
        if (!elementObjects.isEmpty()) {
            Map<String, Object> inferred = infer(elementObjects);
            return inferred != null ? inferred : Map.of("type", "object");
        }
        return Map.of("type", elementType);
    }

    private static String jsonType(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Number) return "number";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        return "string";
    }
}
