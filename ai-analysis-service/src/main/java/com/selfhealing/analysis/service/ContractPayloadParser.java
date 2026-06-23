package com.selfhealing.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes contract payloads loaded from JDBC (Map, JSON string, PGobject, JsonNode).
 */
public final class ContractPayloadParser {

    private ContractPayloadParser() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object contract, ObjectMapper mapper) {
        if (contract == null) return Map.of();

        if (contract instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) out.put(k.toString(), v);
            });
            return out;
        }

        if (contract instanceof JsonNode node) {
            if (!node.isObject()) return Map.of();
            return mapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
        }

        String json = extractJsonString(contract);
        if (json == null || json.isBlank()) return Map.of();

        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String extractJsonString(Object contract) {
        if (contract instanceof String s) return s;

        // org.postgresql.util.PGobject and similar wrappers
        try {
            var getValue = contract.getClass().getMethod("getValue");
            Object value = getValue.invoke(contract);
            if (value instanceof String s) return s;
        } catch (ReflectiveOperationException ignored) {
            // not a PGobject-like type
        }

        return contract.toString();
    }
}
