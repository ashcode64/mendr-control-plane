package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.dto.ApiFailureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads contracts, CORS policy, allowlists, active rules, and runs deterministic analyzers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailureContextEnricher {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FailureAnalysisContext enrich(ApiFailureEvent event) {
        String category = event.getFailureCategory() != null ? event.getFailureCategory() : "UNKNOWN";

        ContractContext contracts = fetchContracts(event.getServiceA(), event.getServiceB(), event.getEndpoint());
        RegistryDiscoveryContext registry = fetchRegistryContext(event);
        CorsPolicyContext corsPolicy = fetchCorsPolicy(event.getServiceB(), event.getEndpoint());
        List<String> upstreamAllowed = loadUpstreamAllowedOrigins(corsPolicy, event.getServiceB());
        List<String> mendrEdgeAllowed = loadMendrEdgeAllowedOrigins(event.getServiceB());
        List<ActiveRuleSummary> activeRules = loadActiveRules(
                event.getServiceA(), event.getServiceB(), event.getEndpoint());

        SchemaDiffResult schemaDiff = SchemaDiffResult.empty();
        ResponseDiffResult responseDiff = ResponseDiffResult.empty();
        CorsUpstreamDiffResult corsUpstreamDiff = CorsUpstreamDiffResult.empty();
        CorsEdgeDiffResult corsEdgeDiff = CorsEdgeDiffResult.empty();

        if ("SCHEMA_MISMATCH".equals(category)) {
            schemaDiff = SchemaMismatchAnalyzer.analyze(
                    event.getRequestPayload(),
                    contracts.senderContract(),
                    contracts.receiverContract(),
                    event.getErrorMessage(),
                    event.getResponsePayload());
            if (schemaDiff.hasDeterministicRule()) {
                log.info("Schema diff for {}: {} — {}", event.getFailureId(), schemaDiff.kind(), schemaDiff.summary());
            }
        }
        if ("RESPONSE_MISMATCH".equals(category)) {
            responseDiff = ResponseMismatchAnalyzer.analyze(
                    ResponseMismatchAnalyzer.extractActualResponse(event.getResponsePayload()),
                    contracts.callerResponseContract(),
                    contracts.providerResponseContract());
            if (responseDiff.hasDeterministicRule()) {
                log.info("Response diff for {}: {} — {}", event.getFailureId(),
                        responseDiff.primaryKind(), responseDiff.summary());
            }
        }
        if ("CORS_UPSTREAM".equals(category)) {
            corsUpstreamDiff = CorsUpstreamAnalyzer.analyze(
                    event.getRequestOrigin(),
                    event.getServiceA(),
                    event.getServiceB(),
                    event.getEndpoint(),
                    corsPolicy,
                    upstreamAllowed,
                    event.getRegisteredBaseUrl(),
                    event.getTargetServiceUrl());
            if (corsUpstreamDiff.hasDeterministicRule()) {
                log.info("CORS upstream diff for {}: {}", event.getFailureId(), corsUpstreamDiff.summary());
            }
        }
        if ("CORS".equals(category)) {
            corsEdgeDiff = CorsEdgeAnalyzer.analyze(
                    event.getRequestOrigin(),
                    event.getServiceB(),
                    mendrEdgeAllowed,
                    event.getRegisteredBaseUrl(),
                    event.getTargetServiceUrl());
            if (corsEdgeDiff.hasDeterministicRule()) {
                log.info("CORS edge diff for {}: {}", event.getFailureId(), corsEdgeDiff.summary());
            }
        }

        return new FailureAnalysisContext(
                event, category, contracts, registry, corsPolicy,
                upstreamAllowed, mendrEdgeAllowed, activeRules,
                schemaDiff, responseDiff, corsUpstreamDiff, corsEdgeDiff);
    }

    private ContractContext fetchContracts(String serviceA, String serviceB, String endpoint) {
        try {
            return new ContractContext(
                    fetchRequestContract(serviceA, endpoint, "1.0"),
                    fetchRequestContract(serviceB, endpoint, "1.0"),
                    fetchResponseContract(serviceA, endpoint, "1.0"),
                    fetchResponseContract(serviceB, endpoint, "1.0"));
        } catch (Exception e) {
            log.debug("Could not fetch contracts: {}", e.getMessage());
            return new ContractContext(null, null, null, null);
        }
    }

    private Object fetchRequestContract(String serviceName, String endpoint, String version) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT example_payload, version FROM service_contracts
            WHERE service_name = ? AND endpoint = ? AND direction = 'REQUEST' AND is_active = true
            ORDER BY CASE WHEN version = ? THEN 0 ELSE 1 END, created_at DESC
            """, serviceName, endpoint, version);

        for (Map<String, Object> row : rows) {
            Object payload = row.get("example_payload");
            Map<String, Object> parsed = ContractPayloadParser.toMap(payload, objectMapper);
            if (isPaymentSchemaPayload(parsed)) return parsed;
        }
        if (rows.isEmpty()) return null;
        return ContractPayloadParser.toMap(rows.get(0).get("example_payload"), objectMapper);
    }

    private Object fetchResponseContract(String serviceName, String endpoint, String version) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT example_payload FROM service_contracts
            WHERE service_name = ? AND endpoint = ? AND direction = 'RESPONSE' AND is_active = true
            ORDER BY CASE WHEN version = ? THEN 0 ELSE 1 END, created_at DESC
            LIMIT 1
            """, serviceName, endpoint, version);
        return rows.isEmpty() ? null
                : ContractPayloadParser.toMap(rows.get(0).get("example_payload"), objectMapper);
    }

    CorsPolicyContext fetchCorsPolicy(String targetService, String endpoint) {
        if (targetService == null || endpoint == null) return CorsPolicyContext.empty();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT example_payload FROM service_contracts
                WHERE service_name = ? AND endpoint = ? AND direction = 'REQUEST'
                  AND version = 'cors-policy' AND is_active = true
                ORDER BY created_at DESC LIMIT 1
                """, targetService, endpoint);
            if (rows.isEmpty()) return CorsPolicyContext.empty();
            Object payload = rows.get(0).get("example_payload");
            Map<String, Object> parsed = ContractPayloadParser.toMap(payload, objectMapper);
            return CorsPolicyContext.fromContract(parsed);
        } catch (Exception e) {
            log.debug("Could not fetch cors-policy for {} {}: {}", targetService, endpoint, e.getMessage());
            return CorsPolicyContext.empty();
        }
    }

    List<String> loadUpstreamAllowedOrigins(CorsPolicyContext corsPolicy, String targetService) {
        Set<String> origins = new LinkedHashSet<>(corsPolicy.upstreamAllowlist());
        if (!origins.isEmpty()) return List.copyOf(origins);

        if (targetService == null) return List.of();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT allowed_caller_origins FROM services
                WHERE name = ? AND is_active = true
                """, targetService);
            if (!rows.isEmpty()) {
                parseOriginList(rows.get(0).get("allowed_caller_origins"), origins);
            }
        } catch (Exception e) {
            log.debug("Could not load allowed_caller_origins for {}: {}", targetService, e.getMessage());
        }
        return List.copyOf(origins);
    }

    List<String> loadMendrEdgeAllowedOrigins(String targetService) {
        if (targetService == null) return List.of();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT allowed_origin FROM cors_rules
                WHERE target_service = ? AND is_active = true
                ORDER BY approved_at DESC NULLS LAST
                """, targetService);
            List<String> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object o = row.get("allowed_origin");
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            }
            return out;
        } catch (Exception e) {
            log.debug("Could not load Mendr edge CORS rules for {}: {}", targetService, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void parseOriginList(Object raw, Set<String> target) {
        if (raw == null) return;
        if (raw instanceof List<?> list) {
            list.forEach(o -> {
                if (o != null && !o.toString().isBlank()) target.add(o.toString().trim());
            });
            return;
        }
        if (raw instanceof String s && s.startsWith("[")) {
            try {
                List<String> parsed = objectMapper.readValue(s, List.class);
                parsed.forEach(o -> {
                    if (o != null && !o.isBlank()) target.add(o.trim());
                });
            } catch (Exception ignored) {
                if (!s.isBlank()) target.add(s.trim());
            }
            return;
        }
        if (raw instanceof String s && !s.isBlank()) {
            target.add(s.trim());
        }
    }

    List<ActiveRuleSummary> loadActiveRules(String serviceA, String serviceB, String endpoint) {
        List<ActiveRuleSummary> rules = new ArrayList<>();
        if (serviceA == null || serviceB == null || endpoint == null) return rules;
        try {
            List<Map<String, Object>> transforms = jdbcTemplate.queryForList("""
                SELECT rule_type, rule_definition FROM transformation_rules
                WHERE service_a = ? AND service_b = ? AND endpoint = ? AND is_active = true
                ORDER BY approved_at DESC NULLS LAST LIMIT 5
                """, serviceA, serviceB, endpoint);
            for (Map<String, Object> row : transforms) {
                rules.add(new ActiveRuleSummary(
                        str(row.get("rule_type")),
                        "transformation",
                        str(row.get("rule_definition"))));
            }

            List<Map<String, Object>> overrides = jdbcTemplate.queryForList("""
                SELECT caller_origin, outbound_origin FROM origin_override_rules
                WHERE source_service = ? AND target_service = ? AND endpoint = ? AND is_active = true
                ORDER BY approved_at DESC NULLS LAST LIMIT 5
                """, serviceA, serviceB, endpoint);
            for (Map<String, Object> row : overrides) {
                rules.add(new ActiveRuleSummary(
                        "CORS_ORIGIN_OVERRIDE",
                        "origin_override",
                        row.get("caller_origin") + " → " + row.get("outbound_origin")));
            }

            List<Map<String, Object>> routing = jdbcTemplate.queryForList("""
                SELECT original_url, new_url FROM routing_rules
                WHERE service_name = ? AND is_active = true
                ORDER BY approved_at DESC NULLS LAST LIMIT 3
                """, serviceB);
            for (Map<String, Object> row : routing) {
                rules.add(new ActiveRuleSummary(
                        "ROUTING_OVERRIDE",
                        "routing",
                        row.get("original_url") + " → " + row.get("new_url")));
            }
        } catch (Exception e) {
            log.debug("Could not load active rules: {}", e.getMessage());
        }
        return rules;
    }

    private RegistryDiscoveryContext fetchRegistryContext(ApiFailureEvent event) {
        String serviceA = event.getServiceA();
        String serviceB = event.getServiceB();
        try {
            List<Map<String, Object>> involved = jdbcTemplate.queryForList("""
                SELECT name, base_url, namespace, k8s_service_name, health_endpoint,
                       last_health_status, last_health_check, is_active, description
                FROM services
                WHERE name IN (?, ?) AND is_active = true
                ORDER BY name
                """, serviceA, serviceB);

            List<Map<String, Object>> allActive = jdbcTemplate.queryForList("""
                SELECT name, base_url, last_health_status
                FROM services
                WHERE is_active = true AND base_url IS NOT NULL
                ORDER BY name
                """);

            List<Map<String, Object>> routingRules = jdbcTemplate.queryForList("""
                SELECT service_name, original_url, new_url, discovery_method, is_active, expires_at
                FROM routing_rules
                WHERE service_name IN (?, ?) AND is_active = true
                ORDER BY approved_at DESC NULLS LAST
                LIMIT 5
                """, serviceA, serviceB);

            List<Map<String, Object>> probes = jdbcTemplate.queryForList("""
                SELECT probed_url, reachable, http_status, response_time_ms, error_message, probed_at
                FROM dns_probe_log
                WHERE service_name = ?
                ORDER BY probed_at DESC
                LIMIT 20
                """, serviceB != null ? serviceB : serviceA);

            return new RegistryDiscoveryContext(involved, routingRules, probes, allActive);
        } catch (Exception e) {
            log.debug("Could not fetch registry context: {}", e.getMessage());
            return new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of());
        }
    }

    private boolean isPaymentSchemaPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return false;
        return payload.containsKey("customerId") || payload.containsKey("amount");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
