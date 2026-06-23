package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes Claude transformation rule JSON into the single-object shape
 * expected by the rule engine and gateway ({@code type} + {@code mappings} map).
 */
public final class TransformationRulesParser {

    private TransformationRulesParser() {}

    public static Map<String, Object> parse(JsonNode rulesNode, ObjectMapper mapper) {
        if (rulesNode == null || rulesNode.isNull() || rulesNode.isMissingNode()) {
            return new HashMap<>();
        }

        if (rulesNode.isArray()) {
            return mergeRuleArray(rulesNode, mapper);
        }

        if (rulesNode.isObject()) {
            return normalizeRuleObject(rulesNode, mapper);
        }

        throw new IllegalArgumentException("transformationRules must be a JSON object or array");
    }

    private static Map<String, Object> mergeRuleArray(JsonNode array, ObjectMapper mapper) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("type", "FIELD_RENAME");
        Map<String, String> allMappings = new LinkedHashMap<>();

        for (JsonNode item : array) {
            if (!item.isObject()) continue;
            Map<String, Object> rule = normalizeRuleObject(item, mapper);
            String type = str(rule.get("type"));
            if (type != null && merged.get("type") == null) {
                merged.put("type", type);
            } else if (type != null && !type.equalsIgnoreCase(str(merged.get("type")))) {
                merged.put("type", "FIELD_RENAME");
            }
            mergeMaps(allMappings, extractStringMap(rule.get("mappings")));
            mergeMaps(allMappings, extractStringMap(rule.get("defaults")));
        }

        if (!allMappings.isEmpty()) {
            merged.put("mappings", allMappings);
        }
        return merged;
    }

    private static Map<String, Object> normalizeRuleObject(JsonNode node, ObjectMapper mapper) {
        Map<String, Object> rule = mapper.convertValue(
                node,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));

        if (rule.containsKey("mappings")) {
            rule.put("mappings", normalizeMappings(rule.get("mappings"), mapper));
        }
        if (rule.containsKey("defaults")) {
            rule.put("defaults", normalizeDefaults(rule.get("defaults"), mapper));
        }
        if (rule.containsKey("coercions")) {
            rule.put("coercions", normalizeMappings(rule.get("coercions"), mapper));
        }
        if (rule.containsKey("type")) {
            rule.put("type", normalizeRuleType(str(rule.get("type"))));
        }
        if (rule.containsKey("endpoint")) {
            rule.put("endpoint", EndpointNormalizer.normalize(str(rule.get("endpoint"))));
        }
        return rule;
    }

    /** Preserve JSON numbers/booleans for ADD_DEFAULT — do not stringify amounts. */
    private static Map<String, Object> normalizeDefaults(Object raw, ObjectMapper mapper) {
        if (raw == null) return new LinkedHashMap<>();

        JsonNode node = mapper.valueToTree(raw);
        if (!node.isObject()) return new LinkedHashMap<>();

        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isNumber()) {
                out.put(entry.getKey(), value.isIntegralNumber() ? value.longValue() : value.doubleValue());
            } else if (value.isBoolean()) {
                out.put(entry.getKey(), value.booleanValue());
            } else if (value.isTextual()) {
                out.put(entry.getKey(), coerceTextDefault(value.asText()));
            } else if (!value.isNull()) {
                out.put(entry.getKey(), mapper.convertValue(value, Object.class));
            }
        });
        return out;
    }

    private static Object coerceTextDefault(String text) {
        if (text == null || text.isBlank()) return text;
        try {
            return text.contains(".") ? Double.parseDouble(text) : Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return text;
        }
    }

    /** Composite AI types like FIELD_RENAME|TYPE_COERCE become NESTED_TRANSFORM at deploy. */
    static String normalizeRuleType(String raw) {
        if (raw == null || raw.isBlank()) return "FIELD_RENAME";
        String upper = raw.toUpperCase().trim();
        if (upper.contains("|") || upper.contains(",")) return "NESTED_TRANSFORM";
        return upper;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> normalizeMappings(Object raw, ObjectMapper mapper) {
        if (raw == null) return new LinkedHashMap<>();

        if (raw instanceof Map<?, ?> map) {
            Map<String, String> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) out.put(k.toString(), v.toString());
            });
            return out;
        }

        JsonNode node = mapper.valueToTree(raw);
        if (node.isObject()) {
            Map<String, String> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            return out;
        }

        if (node.isArray()) {
            Map<String, String> out = new LinkedHashMap<>();
            for (JsonNode item : node) {
                if (item.isObject()) {
                    String from = firstText(item, "from", "source", "old", "oldName", "oldField", "key");
                    String to   = firstText(item, "to", "target", "new", "newName", "newField", "value");
                    if (from != null && to != null) {
                        out.put(from, to);
                    }
                }
            }
            return out;
        }

        return new LinkedHashMap<>();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asText();
            }
        }
        return null;
    }

    private static void mergeMaps(Map<String, String> target, Map<String, String> source) {
        if (source != null) target.putAll(source);
    }

    private static Map<String, String> extractStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) out.put(k.toString(), v.toString());
        });
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
