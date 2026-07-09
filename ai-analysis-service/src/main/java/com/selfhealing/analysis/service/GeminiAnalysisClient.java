package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.context.DeterministicFinding;
import com.selfhealing.analysis.service.context.StructuredFailureContext;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;
import com.selfhealing.analysis.service.tool.AnalysisTools;
import com.selfhealing.analysis.service.tool.ContextToolExecutor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls Google Gemini with function calling — mirrors the three-tier flow in
 * {@link AnthropicAnalysisClient}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiAnalysisClient implements LlmAnalysisClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ContextToolExecutor contextToolExecutor;

    @Value("${llm.provider:anthropic}")
    private String llmProvider;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String model;

    @Value("${gemini.api-url:https://generativelanguage.googleapis.com/v1beta}")
    private String apiUrl;

    @Value("${gemini.max-output-tokens:2000}")
    private int maxOutputTokens;

    @Value("${gemini.agent-loop.max-turns:3}")
    private int maxAgentTurns;

    @PostConstruct
    void logGeminiConfigAtStartup() {
        if (!"gemini".equalsIgnoreCase(llmProvider)) {
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM_PROVIDER=gemini but GEMINI_API_KEY is not set — mock analysis will be used");
        } else {
            log.info("LLM_PROVIDER=gemini active (model={})", model);
        }
    }

    @Override
    public AnalysisToolResult analyze(String systemPrompt,
                                      StructuredFailureContext structuredContext,
                                      FailureAnalysisContext ctx) {
        String userJson = serialize(structuredContext);
        String category = ctx.category();
        DeterministicFinding finding = structuredContext.deterministicFinding();
        boolean deterministic = finding != null && finding.hasConfidentMatch();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GEMINI_API_KEY not set. Using mock analysis.");
            return mock(structuredContext, ctx);
        }

        try {
            if (deterministic) {
                String toolName = AnalysisTools.toolForRuleType(finding.kind());
                if (toolName == null) toolName = forcedToolForCategory(category);
                return singleShot(systemPrompt, userJson,
                        List.of(AnalysisTools.toolByName(toolName)),
                        GeminiToolAdapter.toolConfigForced(toolName));
            }
            if (isKnownCategory(category)) {
                return singleShot(systemPrompt, userJson,
                        AnalysisTools.toolsForCategory(category),
                        GeminiToolAdapter.toolConfigAny());
            }
            return agentLoop(systemPrompt, userJson, category);
        } catch (Exception e) {
            log.error("Gemini function-call failed: {}", e.getMessage(), e);
            return mock(structuredContext, ctx);
        }
    }

    private AnalysisToolResult singleShot(String systemPrompt, String userJson,
                                          List<Map<String, Object>> tools,
                                          Map<String, Object> toolConfig) {
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(userContent(userJson));

        JsonNode response = callApi(systemPrompt, contents, tools, toolConfig);
        JsonNode functionCall = GeminiResponseSupport.firstFunctionCall(response);
        if (functionCall == null) {
            throw new IllegalStateException("Model returned no functionCall");
        }
        return toResult(functionCall);
    }

    private AnalysisToolResult agentLoop(String systemPrompt, String userJson, String category) {
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(userContent(userJson));

        List<Map<String, Object>> tools = new ArrayList<>(ContextToolExecutor.CONTEXT_TOOLS);
        tools.addAll(AnalysisTools.toolsForCategory(category));

        for (int turn = 0; turn < maxAgentTurns; turn++) {
            boolean lastTurn = turn == maxAgentTurns - 1;
            Map<String, Object> toolConfig = lastTurn
                    ? GeminiToolAdapter.toolConfigAny()
                    : GeminiToolAdapter.toolConfigAuto();
            List<Map<String, Object>> turnTools = lastTurn ? AnalysisTools.toolsForCategory(category) : tools;

            JsonNode response = callApi(systemPrompt, contents, turnTools, toolConfig);
            List<JsonNode> functionCalls = GeminiResponseSupport.allFunctionCalls(response);

            JsonNode proposal = firstProposeFunction(functionCalls);
            if (proposal != null) {
                return toResult(proposal);
            }

            if (functionCalls.isEmpty()) {
                contents.add(modelContent(response));
                contents.add(userTextContent("Call one propose_* tool now with your best fix."));
                continue;
            }

            contents.add(modelContent(response));
            List<Map<String, Object>> responseParts = new ArrayList<>();
            for (JsonNode fc : functionCalls) {
                String name = fc.path("name").asText();
                Map<String, Object> args = GeminiResponseSupport.functionArgs(fc, objectMapper);
                Object result = contextToolExecutor.execute(name, args);
                responseParts.add(Map.of(
                        "functionResponse", Map.of(
                                "name", name,
                                "response", result instanceof Map<?, ?> m ? m : Map.of("result", result))));
            }
            contents.add(Map.of("role", "user", "parts", responseParts));
        }

        JsonNode response = callApi(systemPrompt, contents,
                AnalysisTools.toolsForCategory(category), GeminiToolAdapter.toolConfigAny());
        JsonNode functionCall = GeminiResponseSupport.firstFunctionCall(response);
        if (functionCall == null) throw new IllegalStateException("Agent loop ended with no functionCall");
        return toResult(functionCall);
    }

    private JsonNode callApi(String systemPrompt, List<Map<String, Object>> contents,
                             List<Map<String, Object>> anthropicTools,
                             Map<String, Object> toolConfig) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        requestBody.put("contents", contents);
        requestBody.put("tools", List.of(Map.of(
                "functionDeclarations", GeminiToolAdapter.toFunctionDeclarations(anthropicTools))));
        requestBody.put("toolConfig", toolConfig);
        requestBody.put("generationConfig", Map.of("maxOutputTokens", maxOutputTokens));

        WebClient client = webClientBuilder
                .baseUrl(apiUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();

        String responseBody = client.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:generateContent")
                        .queryParam("key", apiKey)
                        .build(model))
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            JsonNode tree = objectMapper.readTree(responseBody);
            GeminiResponseSupport.ensureSuccess(tree);
            return tree;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse Gemini response: " + e.getMessage(), e);
        }
    }

    private AnalysisToolResult toResult(JsonNode functionCall) {
        String name = functionCall.path("name").asText();
        Map<String, Object> input = GeminiResponseSupport.functionArgs(functionCall, objectMapper);
        return AnalysisToolResult.fromToolInput(AnalysisToolResult.Source.GEMINI, model, name, input);
    }

    private JsonNode firstProposeFunction(List<JsonNode> functionCalls) {
        for (JsonNode fc : functionCalls) {
            String name = fc.path("name").asText();
            if (AnalysisTools.ruleTypeForTool(name) != null) return fc;
        }
        return null;
    }

    private Map<String, Object> userContent(String text) {
        return Map.of("role", "user", "parts", List.of(Map.of("text", text)));
    }

    private Map<String, Object> userTextContent(String text) {
        return userContent(text);
    }

    private Map<String, Object> modelContent(JsonNode response) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (JsonNode part : GeminiResponseSupport.responseParts(response)) {
            parts.add(toMap(part));
        }
        return Map.of("role", "model", "parts", parts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return Map.of();
        return objectMapper.convertValue(node, Map.class);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private boolean isKnownCategory(String category) {
        return switch (category == null ? "" : category) {
            case "SCHEMA_MISMATCH", "RESPONSE_MISMATCH", "CORS", "CORS_UPSTREAM", "ROUTING" -> true;
            default -> false;
        };
    }

    private String forcedToolForCategory(String category) {
        return switch (category == null ? "" : category) {
            case "CORS_UPSTREAM" -> "propose_cors_origin_override";
            case "CORS" -> "propose_cors_allow";
            case "ROUTING" -> "propose_routing_override";
            default -> "propose_field_rename";
        };
    }

    AnalysisToolResult mock(StructuredFailureContext sc, FailureAnalysisContext ctx) {
        return MockAnalysis.build(sc, ctx, model);
    }
}
