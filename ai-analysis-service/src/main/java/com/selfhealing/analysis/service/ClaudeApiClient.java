package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeApiClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.api-url:https://api.anthropic.com/v1/messages}")
    private String apiUrl;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${anthropic.max-tokens:2000}")
    private int maxTokens;

    /** Runs once at startup — check console for this before any failure is analyzed. */
    @PostConstruct
    void logAnthropicConfigAtStartup() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY not configured at startup — mock analysis will be used");
        } else {
            log.info("Anthropic API ready at startup (model={})", model);
        }
    }

    /**
     * Send a prompt to Claude and return the text response.
     */
    public String analyzeFailure(String systemPrompt, String userPrompt) {
        return analyzeFailure(systemPrompt, userPrompt, null);
    }

    public String analyzeFailure(String systemPrompt, String userPrompt, FailureAnalysisContext ctx) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY not set. Using mock analysis.");
            return getMockAnalysis(userPrompt, ctx);
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))
            );

            WebClient client = webClientBuilder
                    .baseUrl("https://api.anthropic.com")
                    .defaultHeader("x-api-key", apiKey)
                    .defaultHeader("anthropic-version", "2023-06-01")
                    .defaultHeader("Content-Type", "application/json")
                    .build();

            String responseBody = client.post()
                    .uri("/v1/messages")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("content").get(0).path("text").asText();

        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage(), e);
            return getMockAnalysis(userPrompt, ctx);
        }
    }

    /**
     * Fallback mock analysis when API key is not set (for development/demo).
     */
    private String getMockAnalysis(String prompt, FailureAnalysisContext ctx) {
        // ROUTING failure mock — prefer Mendr registry / DNS probe data from prompt
        if (prompt.contains("ROUTING") || prompt.contains("unreachable") || prompt.contains("Attempted URL")) {
            String serviceName = extractBetween(prompt, "Target Service: ", "\n");
            String attemptedUrl = extractBetween(prompt, "Attempted URL : ", "\n");
            String registeredBase = extractRegisteredBaseUrl(prompt, serviceName);
            String probedReachable = extractReachableProbeUrl(prompt);

            String suggestedNew;
            String discoveryMethod;
            String rootCause;

            if (registeredBase != null && attemptedUrl != null) {
                suggestedNew = mergeBaseUrlWithAttemptedPath(registeredBase, attemptedUrl);
                discoveryMethod = "REGISTRY_LOOKUP";
                rootCause = String.format(
                        "Routing mismatch: gateway attempted '%s' but Mendr registry has '%s' registered for '%s'.",
                        attemptedUrl, registeredBase, serviceName);
            } else if (probedReachable != null) {
                suggestedNew = probedReachable;
                discoveryMethod = "DNS_PROBE";
                rootCause = String.format(
                        "Service '%s' unreachable at '%s'. DNS probe found reachable endpoint '%s'.",
                        serviceName, attemptedUrl, probedReachable);
            } else {
                suggestedNew = attemptedUrl != null
                        ? attemptedUrl.replaceAll(":(\\d+)", ":8091")
                        : "http://unknown-new-host:8091";
                discoveryMethod = "AI_SUGGESTED";
                rootCause = String.format(
                        "Service '%s' unreachable at '%s'. No registry entry found; suggesting port correction.",
                        serviceName, attemptedUrl);
            }

            return String.format("""
                {
                  "rootCause": "%s",
                  "confidence": 0.91,
                  "category": "ROUTING",
                  "transformationRules": {
                    "type": "ROUTING_OVERRIDE",
                    "serviceName": "%s",
                    "originalUrl": "%s",
                    "suggestedNewUrl": "%s",
                    "discoveryMethod": "%s"
                  },
                  "suggestedPermanentFix": "Align gateway routing with Mendr service registry for '%s'. Update seed data or DynamicRoutingService defaults if stale.",
                  "impact": "HIGH"
                }
                """, rootCause, serviceName, attemptedUrl, suggestedNew, discoveryMethod, serviceName);
        }

        // Upstream CORS failure mock (Case 2 — Service B rejected Origin)
        if (prompt.contains("CORS_UPSTREAM") || prompt.contains("UPSTREAM CORS")
                || prompt.contains("do NOT suggest CORS_ALLOW")) {
            String callerOrigin = extractBetween(prompt, "callerOrigin (real Origin from caller envelope): ", "\n");
            if (callerOrigin == null) callerOrigin = extractBetween(prompt, "requestOrigin : ", "\n");
            if (callerOrigin == null) callerOrigin = extractBetween(prompt, "Blocked real Origin: ", "\n");

            String outboundOrigin = extractBetween(prompt, "outboundOrigin (Origin Service B accepts): ", "\n");
            if (outboundOrigin == null && ctx != null && ctx.corsUpstreamDiff().hasDeterministicRule()) {
                outboundOrigin = ctx.corsUpstreamDiff().outboundOrigin();
            }
            if (outboundOrigin == null) {
                outboundOrigin = firstOriginFromList(prompt, "upstreamAllowedOrigins");
            }
            if (outboundOrigin == null) outboundOrigin = "http://localhost:8090";

            String sourceService = extractBetween(prompt, "Source Service: ", "\n");
            if (sourceService != null && sourceService.contains(" (unchanged")) {
                sourceService = sourceService.substring(0, sourceService.indexOf(" (unchanged")).trim();
            }
            String targetService = extractBetween(prompt, "Target Service: ", "\n");
            String endpoint = extractBetween(prompt, "endpointPath: ", "\n");
            if (endpoint == null) endpoint = extractBetween(prompt, "endpointPath  : ", "\n");
            if (endpoint == null) endpoint = "/api/payments/process";
            endpoint = EndpointNormalizer.normalize(endpoint);

            if (callerOrigin == null && ctx != null) callerOrigin = ctx.event().getRequestOrigin();
            if (callerOrigin == null) callerOrigin = "http://order-service-v2:9090";
            if (sourceService == null) sourceService = "order-service";
            if (targetService == null) targetService = "payment-service";
            return String.format("""
                {
                  "rootCause": "Service B rejected the real Origin '%s'; Mendr can rewrite Origin on the wire to '%s' until B is updated.",
                  "confidence": 0.93,
                  "category": "CORS_UPSTREAM",
                  "transformationRules": {
                    "type": "CORS_ORIGIN_OVERRIDE",
                    "sourceService": "%s",
                    "targetService": "%s",
                    "endpoint": "%s",
                    "callerOrigin": "%s",
                    "outboundOrigin": "%s",
                    "rewriteResponseAcao": true
                  },
                  "suggestedPermanentFix": "Add '%s' to the CORS allowed-origins list in Service B's configuration. Deploy the change and remove this temporary override.",
                  "impact": "HIGH"
                }
                """, callerOrigin, outboundOrigin, sourceService, targetService, endpoint,
                    callerOrigin, outboundOrigin, callerOrigin);
        }

        // Mendr edge CORS failure mock (Case 1)
        if (prompt.contains("CORS CONTEXT (Mendr edge)") || prompt.contains("STRUCTURED CORS EDGE DIFF")) {
            String origin = extractBetween(prompt, "blockedOrigin (must become newOrigin in CORS_ALLOW): ", "\n");
            if (origin == null) origin = extractBetween(prompt, "Blocked Origin: ", "\n");
            if (origin == null) origin = extractBetween(prompt, "requestOrigin : ", "\n");
            String targetService = extractBetween(prompt, "Target Service: ", "\n");
            return String.format("""
                {
                  "rootCause": "Service A has a new URL ('%s') which is not in Service B's CORS allowlist. This happens when Service A is redeployed to a new host/port without updating Service B's CORS configuration.",
                  "confidence": 0.92,
                  "category": "CORS",
                  "transformationRules": {
                    "type": "CORS_ALLOW",
                    "targetService": "%s",
                    "newOrigin": "%s",
                    "previousOrigin": null,
                    "allowedMethods": "GET,POST,PUT,DELETE,PATCH,OPTIONS",
                    "allowedHeaders": "*"
                  },
                  "suggestedPermanentFix": "Add '%s' to the CORS allowed-origins list in Service B's configuration (application.yml / Spring Security). Deploy the change and remove this temporary rule.",
                  "impact": "HIGH"
                }
                """, origin, targetService, origin, origin);
        }

        // Schema mismatch mocks
        if (prompt.contains("user_id") && prompt.contains("customer_id")) {
            return """
                {
                  "rootCause": "Field name mismatch. Service A sends 'user_id' but Service B expects 'customer_id'. Common schema drift when teams evolve independently.",
                  "confidence": 0.95,
                  "category": "SCHEMA_MISMATCH",
                  "transformationRules": {
                    "type": "FIELD_RENAME",
                    "mappings": { "user_id": "customer_id" }
                  },
                  "suggestedPermanentFix": "Standardize field naming. Update Service A to emit 'customer_id', add an OpenAPI contract test to prevent drift.",
                  "impact": "HIGH"
                }
                """;
        }

        return """
            {
              "rootCause": "Schema mismatch detected. The request payload does not match the expected contract of the target service.",
              "confidence": 0.82,
              "category": "SCHEMA_MISMATCH",
              "transformationRules": {
                "type": "FIELD_RENAME",
                "mappings": {}
              },
              "suggestedPermanentFix": "Review and align API contracts. Add contract testing to your CI/CD pipeline.",
              "impact": "MEDIUM"
            }
            """;
    }

    private String firstOriginFromList(String prompt, String label) {
        int idx = prompt.indexOf(label);
        if (idx < 0) return null;
        int http = prompt.indexOf("http://", idx);
        if (http < 0) return null;
        int end = prompt.indexOf('"', http);
        if (end < 0) end = prompt.indexOf('\n', http);
        if (end < 0) end = prompt.indexOf(',', http);
        return end > http ? prompt.substring(http, end).trim() : null;
    }

    private String extractBetween(String text, String start, String end) {
        int s = text.indexOf(start);
        if (s < 0) return null;
        s += start.length();
        int e = text.indexOf(end, s);
        return e < 0 ? text.substring(s).trim() : text.substring(s, e).trim();
    }

    /** Parse "payment-service → http://localhost:8091" from registry section. */
    private String extractRegisteredBaseUrl(String prompt, String serviceName) {
        if (serviceName == null) return null;
        String marker = serviceName + " → ";
        int idx = prompt.indexOf(marker);
        if (idx < 0) {
            String registered = extractBetween(prompt, "Registered URL: ", "\n");
            if (registered != null) return registered;
            return null;
        }
        int start = idx + marker.length();
        int end = prompt.indexOf('\n', start);
        String url = (end < 0 ? prompt.substring(start) : prompt.substring(start, end)).trim();
        int bracket = url.indexOf(" [");
        return bracket > 0 ? url.substring(0, bracket).trim() : url;
    }

    private String extractReachableProbeUrl(String prompt) {
        int section = prompt.indexOf("Recent DNS/health probes");
        if (section < 0) return null;
        String probes = prompt.substring(section);
        for (String line : probes.split("\n")) {
            if (line.contains("REACHABLE")) {
                int dash = line.indexOf(" - ");
                if (dash >= 0) {
                    String url = line.substring(dash + 3, line.indexOf(" ✓")).trim();
                    if (!url.isBlank()) return url;
                }
            }
        }
        return null;
    }

    /** Base URL for ROUTING_OVERRIDE: same host as attempted URL, port from registry. */
    private String mergeBaseUrlWithAttemptedPath(String registeredBase, String attemptedUrl) {
        try {
            java.net.URI reg = java.net.URI.create(registeredBase);
            java.net.URI att = java.net.URI.create(attemptedUrl);
            String host = att.getHost() != null ? att.getHost() : reg.getHost();
            int port = reg.getPort() > 0 ? reg.getPort() : (reg.getScheme().equals("https") ? 443 : 80);
            return reg.getScheme() + "://" + host + ":" + port;
        } catch (Exception e) {
            return registeredBase;
        }
    }
}
