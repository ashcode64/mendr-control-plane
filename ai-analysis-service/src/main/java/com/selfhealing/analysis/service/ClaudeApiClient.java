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
 * Calls Claude with Anthropic tool-use. Output is always a {@code tool_use} block,
 * so the rule type is the tool name and the parameters are schema-validated input —
 * no free-text JSON to scrape. Three tiers by cost:
 *
 * <ul>
 *   <li>Tier 1 (deterministic): force the single matching tool — type can't be wrong.</li>
 *   <li>Tier 2 (known category): offer category-scoped tools, {@code tool_choice=any}.</li>
 *   <li>Tier 3 (UNKNOWN / low-conf): bounded agent loop with read-only context tools,
 *       then a forced {@code propose_*} tool.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeApiClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ContextToolExecutor contextToolExecutor;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.api-url:https://api.anthropic.com/v1/messages}")
    private String apiUrl;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${anthropic.max-tokens:2000}")
    private int maxTokens;

    @Value("${anthropic.agent-loop.max-turns:3}")
    private int maxAgentTurns;

    @PostConstruct
    void logAnthropicConfigAtStartup() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY not configured at startup — mock analysis will be used");
        } else {
            log.info("Anthropic API ready at startup (model={})", model);
        }
    }

    /**
     * Run analysis for the given structured context and return a typed result.
     * Picks the tier from the context (deterministic finding / known category / UNKNOWN).
     */
    public AnalysisToolResult analyze(String systemPrompt,
                                      StructuredFailureContext structuredContext,
                                      FailureAnalysisContext ctx) {
        String userJson = serialize(structuredContext);
        String category = ctx.category();
        DeterministicFinding finding = structuredContext.deterministicFinding();
        boolean deterministic = finding != null && finding.hasConfidentMatch();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY not set. Using mock analysis.");
            return mock(structuredContext, ctx);
        }

        try {
            if (deterministic) {
                String toolName = AnalysisTools.toolForRuleType(finding.kind());
                if (toolName == null) toolName = forcedToolForCategory(category);
                return singleShot(systemPrompt, userJson, category,
                        List.of(AnalysisTools.toolByName(toolName)),
                        Map.of("type", "tool", "name", toolName));
            }
            if (isKnownCategory(category)) {
                return singleShot(systemPrompt, userJson, category,
                        AnalysisTools.toolsForCategory(category),
                        Map.of("type", "any"));
            }
            return agentLoop(systemPrompt, userJson, category);
        } catch (Exception e) {
            log.error("Claude tool-use call failed: {}", e.getMessage(), e);
            return mock(structuredContext, ctx);
        }
    }

    // ── Tier 1 / 2: single round trip ────────────────────────────────────────────

    private AnalysisToolResult singleShot(String systemPrompt, String userJson, String category,
                                          List<Map<String, Object>> tools, Map<String, Object> toolChoice) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userJson));

        JsonNode response = callApi(systemPrompt, messages, tools, toolChoice);
        JsonNode toolUse = firstToolUse(response);
        if (toolUse == null) {
            throw new IllegalStateException("Model returned no tool_use block");
        }
        return toResult(toolUse);
    }

    // ── Tier 3: bounded agent loop with read-only context tools ─────────────────

    private AnalysisToolResult agentLoop(String systemPrompt, String userJson, String category) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userJson));

        List<Map<String, Object>> tools = new ArrayList<>(ContextToolExecutor.CONTEXT_TOOLS);
        tools.addAll(AnalysisTools.toolsForCategory(category));

        for (int turn = 0; turn < maxAgentTurns; turn++) {
            boolean lastTurn = turn == maxAgentTurns - 1;
            // On the final turn, force a propose_* decision; otherwise let it explore or decide.
            Map<String, Object> toolChoice = lastTurn
                    ? Map.of("type", "any")
                    : Map.of("type", "auto");
            List<Map<String, Object>> turnTools = lastTurn ? AnalysisTools.toolsForCategory(category) : tools;

            JsonNode response = callApi(systemPrompt, messages, turnTools, toolChoice);
            List<JsonNode> toolUses = allToolUses(response);

            JsonNode proposal = firstProposeTool(toolUses);
            if (proposal != null) {
                return toResult(proposal);
            }

            if (toolUses.isEmpty()) {
                // No tool call at all — nudge once more or break.
                messages.add(assistantContent(response));
                messages.add(Map.of("role", "user",
                        "content", "Call one propose_* tool now with your best fix."));
                continue;
            }

            // Execute context tools and feed results back.
            messages.add(assistantContent(response));
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (JsonNode tu : toolUses) {
                String name = tu.path("name").asText();
                String id = tu.path("id").asText();
                Map<String, Object> input = toMap(tu.path("input"));
                Object result = contextToolExecutor.execute(name, input);
                toolResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", id,
                        "content", serialize(result)));
            }
            messages.add(Map.of("role", "user", "content", toolResults));
        }

        // Exhausted turns without a proposal: force one explicit final call.
        JsonNode response = callApi(systemPrompt, messages,
                AnalysisTools.toolsForCategory(category), Map.of("type", "any"));
        JsonNode toolUse = firstToolUse(response);
        if (toolUse == null) throw new IllegalStateException("Agent loop ended with no tool_use");
        return toResult(toolUse);
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────────

    private JsonNode callApi(String systemPrompt, List<Map<String, Object>> messages,
                             List<Map<String, Object>> tools, Map<String, Object> toolChoice) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("system", systemPrompt);
        requestBody.put("messages", messages);
        requestBody.put("tools", tools);
        requestBody.put("tool_choice", toolChoice);

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

        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse Anthropic response: " + e.getMessage(), e);
        }
    }

    private AnalysisToolResult toResult(JsonNode toolUse) {
        String name = toolUse.path("name").asText();
        Map<String, Object> input = toMap(toolUse.path("input"));
        return AnalysisToolResult.fromToolInput(AnalysisToolResult.Source.CLAUDE, model, name, input);
    }

    // ── Response helpers ────────────────────────────────────────────────────────

    private JsonNode firstToolUse(JsonNode response) {
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) return block;
        }
        return null;
    }

    private List<JsonNode> allToolUses(JsonNode response) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) out.add(block);
        }
        return out;
    }

    private JsonNode firstProposeTool(List<JsonNode> toolUses) {
        for (JsonNode tu : toolUses) {
            String name = tu.path("name").asText();
            if (AnalysisTools.ruleTypeForTool(name) != null) return tu;
        }
        return null;
    }

    private Map<String, Object> assistantContent(JsonNode response) {
        List<Object> content = new ArrayList<>();
        for (JsonNode block : response.path("content")) {
            content.add(toMap(block));
        }
        return Map.of("role", "assistant", "content", content);
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

    // ── Mock fallback: same typed result, no API key needed ──────────────────────

    AnalysisToolResult mock(StructuredFailureContext sc, FailureAnalysisContext ctx) {
        return MockAnalysis.build(sc, ctx, model);
    }
}
