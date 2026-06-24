package com.selfhealing.analysis.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.context.TopologyContext;
import com.selfhealing.analysis.service.FailureContextEnricher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only context tools the Tier-3 agent loop can call to pull additional facts
 * instead of being handed everything up front. Each method is a pure lookup over
 * the same data {@link FailureContextEnricher} uses; nothing here mutates state.
 *
 * <p>These definitions are also the natural unit to expose over an MCP server so
 * external agents can investigate Mendr failures with the identical toolset.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextToolExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FailureContextEnricher enricher;

    public static final List<Map<String, Object>> CONTEXT_TOOLS = List.of(
            toolDef("get_contract",
                    "Fetch the registered example contract for a service endpoint and direction.",
                    Map.of(
                            "service", strProp(),
                            "endpoint", strProp(),
                            "direction", Map.of("type", "string", "description", "REQUEST or RESPONSE")),
                    List.of("service", "endpoint", "direction")),
            toolDef("get_service_topology",
                    "Fetch the manifest-derived dependency graph for a service (who it calls, who calls it).",
                    Map.of("service", strProp()),
                    List.of("service")),
            toolDef("get_active_rules",
                    "List the currently active transformation / origin-override / routing rules on a route.",
                    Map.of(
                            "sourceService", strProp(),
                            "targetService", strProp(),
                            "endpoint", strProp()),
                    List.of("sourceService", "targetService", "endpoint")),
            toolDef("get_recent_dns_probes",
                    "List recent DNS/health probe results for a service (newest first).",
                    Map.of("service", strProp()),
                    List.of("service")),
            toolDef("get_similar_past_failures",
                    "Find recent past failures matching a source/target/endpoint signature and how they were resolved.",
                    Map.of(
                            "sourceService", strProp(),
                            "targetService", strProp(),
                            "endpoint", strProp()),
                    List.of("sourceService", "targetService", "endpoint")));

    /** Dispatch a context-tool call; returns a JSON-serializable result map. */
    public Object execute(String toolName, Map<String, Object> input) {
        try {
            return switch (toolName) {
                case "get_contract" -> getContract(s(input, "service"), s(input, "endpoint"), s(input, "direction"));
                case "get_service_topology" -> getTopology(s(input, "service"));
                case "get_active_rules" -> getActiveRules(
                        s(input, "sourceService"), s(input, "targetService"), s(input, "endpoint"));
                case "get_recent_dns_probes" -> getRecentProbes(s(input, "service"));
                case "get_similar_past_failures" -> getSimilarFailures(
                        s(input, "sourceService"), s(input, "targetService"), s(input, "endpoint"));
                default -> Map.of("error", "unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.debug("Context tool {} failed: {}", toolName, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    public boolean isContextTool(String name) {
        return CONTEXT_TOOLS.stream().anyMatch(t -> name.equals(t.get("name")));
    }

    private Object getContract(String service, String endpoint, String direction) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT example_payload, version, description FROM service_contracts
            WHERE service_name = ? AND endpoint = ? AND direction = ? AND is_active = true
            ORDER BY created_at DESC LIMIT 5
            """, service, endpoint, direction == null ? "REQUEST" : direction.toUpperCase());
        if (rows.isEmpty()) return Map.of("found", false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("examples", rows.stream().map(r -> Map.of(
                "version", String.valueOf(r.get("version")),
                "description", String.valueOf(r.get("description")),
                "payload", r.get("example_payload"))).toList());
        return out;
    }

    private Object getTopology(String service) {
        TopologyContext topo = enricher.loadTopology(service, null, null);
        return topo == null ? Map.of("found", false) : topo;
    }

    private Object getActiveRules(String source, String target, String endpoint) {
        return enricher.loadActiveRules(source, target, endpoint);
    }

    private Object getRecentProbes(String service) {
        return jdbcTemplate.queryForList("""
            SELECT probed_url, reachable, http_status, probed_at FROM dns_probe_log
            WHERE service_name = ? ORDER BY probed_at DESC LIMIT 20
            """, service);
    }

    private Object getSimilarFailures(String source, String target, String endpoint) {
        return jdbcTemplate.queryForList("""
            SELECT id, error_type, error_message, status, detected_at
            FROM api_failures
            WHERE service_a = ? AND service_b = ? AND endpoint = ?
            ORDER BY detected_at DESC LIMIT 10
            """, source, target, endpoint);
    }

    private static String s(Map<String, Object> input, String key) {
        Object v = input == null ? null : input.get(key);
        return v == null ? null : v.toString();
    }

    private static Map<String, Object> strProp() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> toolDef(String name, String description,
                                               Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("input_schema", schema);
        return tool;
    }
}
