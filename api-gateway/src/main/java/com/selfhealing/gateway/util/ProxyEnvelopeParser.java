package com.selfhealing.gateway.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.dto.ProxyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ProxyEnvelopeParser {

    private final ObjectMapper objectMapper;

    public ParsedEnvelope parse(byte[] rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String sourceService = text(root, "sourceService");
            String targetService = text(root, "targetService");
            String endpoint = text(root, "endpoint");
            String method = text(root, "method", "POST");
            Map<String, String> headers = parseHeaders(root.get("headers"));

            JsonNode payloadNode = root.get("payload");
            byte[] payloadBytes = payloadNode != null && !payloadNode.isNull()
                    ? objectMapper.writeValueAsBytes(payloadNode)
                    : new byte[0];

            Map<String, Object> payloadMap = null;
            if (payloadNode != null && !payloadNode.isNull() && payloadNode.isObject()) {
                payloadMap = objectMapper.convertValue(payloadNode, new TypeReference<>() {});
            } else if (payloadNode != null && !payloadNode.isNull()) {
                payloadMap = Map.of("_value", objectMapper.convertValue(payloadNode, Object.class));
            }

            ProxyRequest request = ProxyRequest.builder()
                    .sourceService(sourceService)
                    .targetService(targetService)
                    .endpoint(endpoint)
                    .method(method)
                    .payload(payloadMap)
                    .headers(headers)
                    .build();

            return new ParsedEnvelope(request, payloadBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid proxy envelope JSON", e);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private static String text(JsonNode root, String field, String defaultValue) {
        String value = text(root, field);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private static Map<String, String> parseHeaders(JsonNode headersNode) {
        if (headersNode == null || headersNode.isNull() || !headersNode.isObject()) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isNull()) {
                headers.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return headers;
    }

    public record ParsedEnvelope(ProxyRequest request, byte[] payloadBytes) {}
}
