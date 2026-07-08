package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses and validates Gemini {@code generateContent} JSON responses.
 */
public final class GeminiResponseSupport {

    private GeminiResponseSupport() {}

    public static void ensureSuccess(JsonNode response) {
        if (response == null || response.isNull()) {
            throw new IllegalStateException("Gemini returned an empty response body");
        }
        if (response.has("error")) {
            String message = response.path("error").path("message").asText("unknown Gemini API error");
            throw new IllegalStateException("Gemini API error: " + message);
        }
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String blockReason = response.path("promptFeedback").path("blockReason").asText("");
            if (!blockReason.isBlank()) {
                throw new IllegalStateException("Gemini returned no candidates (blocked: " + blockReason + ")");
            }
            throw new IllegalStateException("Gemini returned no candidates");
        }
    }

    public static List<JsonNode> responseParts(JsonNode response) {
        ensureSuccess(response);
        JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
        List<JsonNode> out = new ArrayList<>();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                out.add(part);
            }
        }
        return out;
    }

    public static JsonNode firstFunctionCall(JsonNode response) {
        for (JsonNode part : responseParts(response)) {
            if (part.has("functionCall")) {
                return part.path("functionCall");
            }
        }
        return null;
    }

    public static List<JsonNode> allFunctionCalls(JsonNode response) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode part : responseParts(response)) {
            if (part.has("functionCall")) {
                out.add(part.path("functionCall"));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> functionArgs(JsonNode functionCall, ObjectMapper objectMapper) {
        JsonNode args = functionCall.path("args");
        if (args.isMissingNode() || args.isNull()) {
            return Map.of();
        }
        if (args.isTextual()) {
            try {
                return objectMapper.readValue(args.asText(), Map.class);
            } catch (Exception e) {
                return Map.of();
            }
        }
        return objectMapper.convertValue(args, Map.class);
    }
}
