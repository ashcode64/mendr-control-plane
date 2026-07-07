package com.selfhealing.analysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.tool.ContextToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal MCP (Model Context Protocol) endpoint exposing the same read-only
 * context tools the Tier-3 agent loop uses, so external agents (Cursor, Claude
 * Desktop, etc.) can investigate Mendr failures with the identical toolset.
 *
 * <p>Implements JSON-RPC 2.0 over HTTP for {@code initialize}, {@code tools/list},
 * and {@code tools/call}. Read-only by construction — it only delegates to
 * {@link ContextToolExecutor}, which performs lookups and never mutates state.
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ContextToolExecutor contextToolExecutor;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Map<String, Object>> rpc(@RequestBody Map<String, Object> request) {
        Object id = request.get("id");
        String method = String.valueOf(request.get("method"));
        try {
            return switch (method) {
                case "initialize" -> ResponseEntity.ok(result(id, initializeResult()));
                case "tools/list" -> ResponseEntity.ok(result(id, Map.of("tools", ContextToolExecutor.CONTEXT_TOOLS)));
                case "tools/call" -> ResponseEntity.ok(result(id, callTool(request)));
                case "ping" -> ResponseEntity.ok(result(id, Map.of()));
                default -> ResponseEntity.ok(error(id, -32601, "Method not found: " + method));
            };
        } catch (Exception e) {
            log.warn("MCP rpc {} failed: {}", method, e.getMessage());
            return ResponseEntity.ok(error(id, -32603, e.getMessage()));
        }
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "mendr-analysis");
        info.put("version", "1.0.0");
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", info);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> request) {
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());
        String name = String.valueOf(params.get("name"));
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        if (!contextToolExecutor.isContextTool(name)) {
            return Map.of(
                    "isError", true,
                    "content", List.of(textBlock("Unknown tool: " + name)));
        }

        Object toolResult = contextToolExecutor.execute(name, arguments);
        return Map.of("content", List.of(textBlock(serialize(toolResult))));
    }

    private Map<String, Object> textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    private Map<String, Object> result(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return resp;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", Map.of("code", code, "message", message == null ? "error" : message));
        return resp;
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
