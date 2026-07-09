package com.selfhealing.analysis.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Anthropic-style tool definitions ({@code input_schema}) to Gemini
 * {@code functionDeclarations} ({@code parameters}).
 */
public final class GeminiToolAdapter {

    private GeminiToolAdapter() {}

    public static List<Map<String, Object>> toFunctionDeclarations(List<Map<String, Object>> anthropicTools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> tool : anthropicTools) {
            out.add(toFunctionDeclaration(tool));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toFunctionDeclaration(Map<String, Object> anthropicTool) {
        Map<String, Object> decl = new LinkedHashMap<>();
        decl.put("name", anthropicTool.get("name"));
        decl.put("description", anthropicTool.get("description"));
        Object schema = anthropicTool.get("input_schema");
        if (schema instanceof Map<?, ?> schemaMap) {
            decl.put("parameters", new LinkedHashMap<>((Map<String, Object>) schemaMap));
        } else {
            decl.put("parameters", Map.of("type", "object", "properties", Map.of()));
        }
        return decl;
    }

    public static Map<String, Object> toolConfigAny() {
        return Map.of("functionCallingConfig", Map.of("mode", "ANY"));
    }

    public static Map<String, Object> toolConfigAuto() {
        return Map.of("functionCallingConfig", Map.of("mode", "AUTO"));
    }

    public static Map<String, Object> toolConfigForced(String functionName) {
        return Map.of("functionCallingConfig", Map.of(
                "mode", "ANY",
                "allowedFunctionNames", List.of(functionName)));
    }
}
