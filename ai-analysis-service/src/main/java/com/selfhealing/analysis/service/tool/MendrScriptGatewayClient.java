package com.selfhealing.analysis.service.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        try {
            return client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (apiKey != null && !apiKey.isBlank()) h.set("X-Internal-Api-Key", apiKey); })
                    .bodyValue(body == null ? Map.of() : body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (Exception e) {
            log.warn("MendrScript gateway call {} failed: {}", path, e.getMessage());
            return Map.of("valid", false, "errors", java.util.List.of("gateway unreachable: " + e.getMessage()));
        }
    }
}
