package com.selfhealing.analysis.service.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin client to the api-gateway's authoritative MendrScript verifier/simulator
 * (the single owner of the edge contract). The conversation engine reaches these
 * via the MCP tools {@code verify_program} / {@code simulate_transform}; routing
 * them to the gateway means there is exactly ONE implementation of "is this program
 * safe / what does it do" — no drift between synthesis-time advice and deploy-time
 * enforcement. These calls are READ-ONLY (no deploy capability).
 */
@Slf4j
@Component
public class MendrScriptGatewayClient {

    private final WebClient client;
    private final String apiKey;

    public MendrScriptGatewayClient(WebClient.Builder builder,
                                    @Value("${mendr.gateway.base-url:http://api-gateway:8080}") String baseUrl,
                                    @Value("${gateway.internal.api-key:}") String apiKey) {
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public Map<String, Object> verify(Object program) {
        return post("/api/internal/mendrscript/verify", program);
    }

    public Map<String, Object> simulate(Object simulateRequest) {
        return post("/api/internal/mendrscript/simulate", simulateRequest);
    }

    public Map<String, Object> verifyProperties(Object verifyPropertiesRequest) {
        return post("/api/internal/mendrscript/verify-properties", verifyPropertiesRequest);
    }

    public Map<String, Object> minimize(Object minimizeRequest) {
        Map<String, Object> result = post("/api/internal/mendrscript/minimize", minimizeRequest);
        // Normalize verify-shaped transport errors into the minimize response contract.
        if (result != null && result.containsKey("valid") && !result.containsKey("program")) {
            return Map.of(
                    "program", minimizeRequest instanceof Map<?, ?> m
                            ? m.getOrDefault("program", Map.of()) : Map.of(),
                    "minimized", false,
                    "layersApplied", List.of(),
                    "fellBack", true,
                    "engine", "gateway_unreachable",
                    "errors", result.getOrDefault("errors", List.of("gateway unreachable"))
            );
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        try {
            return client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (apiKey != null && !apiKey.isBlank()) h.set("X-Internal-Api-Key", apiKey); })
                    .bodyValue(body == null ? Map.of() : body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            log.warn("MendrScript gateway call {} failed: {}", path, e.getMessage());
            if (path != null && path.endsWith("/minimize")) {
                Object program = body instanceof Map<?, ?> m ? m.get("program") : Map.of();
                return Map.of(
                        "program", program == null ? Map.of() : program,
                        "minimized", false,
                        "layersApplied", List.of(),
                        "fellBack", true,
                        "engine", "gateway_unreachable",
                        "errors", List.of("gateway unreachable: " + e.getMessage())
                );
            }
            return Map.of("valid", false, "errors", java.util.List.of("gateway unreachable: " + e.getMessage()));
        }
    }
}
