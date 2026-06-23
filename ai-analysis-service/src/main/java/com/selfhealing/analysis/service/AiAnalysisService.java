package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.repository.AnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Category-aware AI analysis with contract enrichment.
 *
 * If a service has registered example payloads (contracts),
 * they are fetched and injected into the prompt so Claude can
 * compare "what was sent" vs "what was expected" with precision.
 *
 * Five categories produce five distinct prompt + rule type strategies:
 *   SCHEMA_MISMATCH  → FIELD_RENAME / TYPE_COERCE / ADD_DEFAULT / REMOVE_FIELD
 *   RESPONSE_MISMATCH→ RESPONSE_FIELD_RENAME / RESPONSE_TYPE_COERCE / etc.
 *   ROUTING          → ROUTING_OVERRIDE
 *   CORS             → CORS_ALLOW (Mendr edge)
 *   CORS_UPSTREAM    → CORS_ORIGIN_OVERRIDE (Service B rejected Origin)
 *   UNKNOWN          → generic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final ClaudeApiClient          claudeClient;
    private final AnalysisResultRepository analysisRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper             objectMapper;
    private final JdbcTemplate             jdbcTemplate;
    private final FailureContextEnricher   contextEnricher;

    @Value("${anthropic.confidence-threshold:0.75}")
    private double confidenceThreshold;

    private static final String TOPIC = "api.analysis.results";

    // ── System Prompts ────────────────────────────────────────────────────────

    private static final String SYS_SCHEMA = """
        You are an expert distributed systems engineer specialising in API contract analysis.
        Analyse the schema mismatch between two microservices using the failure details and,
        where provided, the registered example payloads (contracts).
        Respond ONLY with valid JSON. No markdown, no code blocks, no preamble.
        {
          "rootCause": "concise description",
          "confidence": 0.XX,
          "category": "SCHEMA_MISMATCH",
          "transformationRules": {
            "type": "FIELD_RENAME|ADD_DEFAULT|TYPE_COERCE|REMOVE_FIELD",
            "mappings": { "old_field": "new_field" },
            "defaults": {},
            "coercions": {},
            "fields": []
          },
          "suggestedPermanentFix": "dev team action",
          "impact": "HIGH|MEDIUM|LOW"
        }
        MANDATORY schema analysis priority (when STRUCTURED SCHEMA DIFF section is present, follow it):
        1. Actual has FEWER fields than receiver contract → ADD_DEFAULT only
        2. Same field count + name mismatches (e.g. snake_case vs camelCase) → FIELD_RENAME only
        3. Same field count + type mismatches → TYPE_COERCE only
        Compare actual vs receiver FIELD COUNTS first; do NOT suggest ADD_DEFAULT when counts match.
        MANDATORY for schema mismatches with multiple field renames:
        - transformationRules MUST be a single JSON object, NEVER an array.
        - Put ALL field renames in one mappings object (e.g. customer_id→customerId, total_amount→amount).
        - Do NOT use array form for mappings; use { "snake_field": "camelField" } only.
        MANDATORY for TYPE_COERCE:
        - coercions values MUST be one of: double, integer, long, string, boolean, decimal
        - Example: { "type": "TYPE_COERCE", "coercions": { "amount": "double" } }
        MANDATORY for missing required fields (e.g. "amount is required" with camelCase payload already correct):
        - Use type ADD_DEFAULT ONLY — do NOT suggest FIELD_RENAME when snake_case fields are absent from the request.
        - defaults.amount MUST be a JSON number greater than 0 (e.g. 99.99), NEVER a string ("0.0") and NEVER zero.
        - Example: { "type": "ADD_DEFAULT", "defaults": { "amount": 99.99 } }
        MANDATORY rule type format:
        - type MUST be exactly ONE value: FIELD_RENAME, ADD_DEFAULT, TYPE_COERCE, or REMOVE_FIELD
        - NEVER combine types with | or commas; use NESTED_TRANSFORM only if multiple sections are truly needed in one rule.
        Include only keys relevant to the rule type. Confidence reflects genuine certainty.
        Never suggest rules that could cause data loss.
        """;

    private static final String SYS_RESPONSE = """
        You are an expert distributed systems engineer specialising in API response contract analysis.
        Service B's RESPONSE body does not match what Service A expects.
        Analyse using the failure details and registered contracts where provided.
        Respond ONLY with valid JSON. No markdown, no code blocks, no preamble.
        {
          "rootCause": "concise description of the response mismatch",
          "confidence": 0.XX,
          "category": "RESPONSE_MISMATCH",
          "transformationRules": {
            "type": "RESPONSE_FIELD_RENAME|RESPONSE_ADD_DEFAULT|RESPONSE_TYPE_COERCE|RESPONSE_REMOVE_FIELD|RESPONSE_WRAP|RESPONSE_UNWRAP",
            "mappings": {}, "defaults": {}, "coercions": {}, "fields": [], "key": ""
          },
          "suggestedPermanentFix": "dev team action",
          "impact": "HIGH|MEDIUM|LOW"
        }
        MANDATORY when STRUCTURED RESPONSE DIFF section is present:
        1. Missing response fields → RESPONSE_ADD_DEFAULT only
        2. Field name mismatches → RESPONSE_FIELD_RENAME only
        3. Type mismatches → RESPONSE_TYPE_COERCE only
        Propose exactly ONE rule for the primary classification; remaining issues are fixed on later retries.
        For RESPONSE_ADD_DEFAULT, defaults must use JSON numbers (not strings) for numeric fields.
        For RESPONSE_TYPE_COERCE, coercions values: double, integer, long, string, boolean, decimal.
        Include only keys relevant to the rule type.
        """;

    private static final String SYS_ROUTING = """
        You are an expert SRE specialising in Kubernetes service mesh routing and DNS.
        A microservice cannot reach its target because the URL is wrong or unreachable.
        Respond ONLY with valid JSON. No markdown, no code blocks, no preamble.
        {
          "rootCause": "concise description",
          "confidence": 0.XX,
          "category": "ROUTING",
          "transformationRules": {
            "type": "ROUTING_OVERRIDE",
            "serviceName": "service-that-moved",
            "originalUrl": "http://old-host:port",
            "suggestedNewUrl": "http://new-host:port",
            "discoveryMethod": "REGISTRY_LOOKUP|DNS_PROBE|AI_SUGGESTED"
          },
          "suggestedPermanentFix": "update k8s Service / DNS / ConfigMap",
          "impact": "HIGH"
        }
        MANDATORY when SERVICE REGISTRY & DISCOVERY section is present:
        - MUST set suggestedNewUrl when registry base_url exists and port differs from attempted URL.
        - MUST NOT set suggestedNewUrl to null when confidence >= 0.7 and registry or DNS probe data exists.
        - Use host from originalUrl/attempted URL, port from registered base_url (e.g. attempted payment-service:8092 + registry localhost:8091 → http://payment-service:8091).
        - Prefer DNS probe REACHABLE URLs with discoveryMethod DNS_PROBE.
        - Use discoveryMethod REGISTRY_LOOKUP when suggestedNewUrl comes from registered base_url.
        - suggestedNewUrl must be base URL only (scheme + host + port), no path suffix.
        - Only set suggestedNewUrl to null if no registry/probe data exists AND confidence < 0.7.
        """;

    private static final String SYS_CORS = """
        You are an expert web security engineer specialising in CORS policy and microservices.
        Mendr edge blocked the request before it reached Service B (Case 1 — edge CORS gate).
        Respond ONLY with valid JSON. No markdown, no code blocks, no preamble.
        {
          "rootCause": "concise description",
          "confidence": 0.XX,
          "category": "CORS",
          "transformationRules": {
            "type": "CORS_ALLOW",
            "targetService": "service-b-name",
            "newOrigin": "http://new-service-a-host:port",
            "previousOrigin": "http://old-service-a-host:port",
            "allowedMethods": "GET,POST,PUT,DELETE,PATCH,OPTIONS",
            "allowedHeaders": "*"
          },
          "suggestedPermanentFix": "update CORS config in Service B",
          "impact": "HIGH"
        }
        Only suggest a specific known origin, never wildcard '*'.
        """;

    private static final String SYS_CORS_UPSTREAM = """
        You are an expert web security engineer specialising in CORS policy and microservices.
        Service B rejected the caller's real Origin AFTER Mendr forwarded the request (Case 2 — upstream CORS).
        Mendr must rewrite the outbound Origin header only — never change sourceService identity.
        Respond ONLY with valid JSON. No markdown, no code blocks, no preamble.
        {
          "rootCause": "concise description",
          "confidence": 0.XX,
          "category": "CORS_UPSTREAM",
          "transformationRules": {
            "type": "CORS_ORIGIN_OVERRIDE",
            "sourceService": "service-a-name",
            "targetService": "service-b-name",
            "endpoint": "/api/path",
            "callerOrigin": "http://real-caller-origin:port",
            "outboundOrigin": "http://allowed-origin-service-b-accepts:port",
            "rewriteResponseAcao": true
          },
          "suggestedPermanentFix": "Update Service B CORS allowlist to include the new caller URL",
          "impact": "HIGH"
        }
        outboundOrigin must be an origin Service B already allows (from upstreamAllowedOrigins / cors-policy contract).
        Do NOT suggest CORS_ALLOW — the Mendr edge already passed this request.

        """ + AnalysisPrompts.SYS_CORS_UPSTREAM_NEGATIVE + """
        """;

    private static final String SYS_UNKNOWN = """
        You are an expert distributed systems engineer.
        Analyse this API failure and respond ONLY with valid JSON. No markdown.
        {
          "rootCause": "description",
          "confidence": 0.XX,
          "category": "SCHEMA_MISMATCH|RESPONSE_MISMATCH|ROUTING|CORS|UNKNOWN",
          "transformationRules": { "type": "UNKNOWN" },
          "suggestedPermanentFix": "recommendation",
          "impact": "HIGH|MEDIUM|LOW"
        }
        """;

    // ── Public API ────────────────────────────────────────────────────────────

    public AnalysisResult analyze(ApiFailureEvent event) {
        FailureAnalysisContext ctx = contextEnricher.enrich(event);
        String category = ctx.category();
        log.info("Analysing failure {} (category: {})", event.getFailureId(), category);

        String systemPrompt = switch (category) {
            case "ROUTING"           -> SYS_ROUTING;
            case "CORS"              -> SYS_CORS;
            case "CORS_UPSTREAM"     -> SYS_CORS_UPSTREAM;
            case "RESPONSE_MISMATCH" -> SYS_RESPONSE;
            case "SCHEMA_MISMATCH"   -> SYS_SCHEMA;
            default                  -> SYS_UNKNOWN;
        };

        String userPrompt  = buildPrompt(ctx);
        String rawResponse = claudeClient.analyzeFailure(systemPrompt, userPrompt, ctx);
        AnalysisResult result = parseAndSave(rawResponse, ctx);
        publishResult(result, event, ctx);
        return result;
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    private String buildPrompt(FailureAnalysisContext ctx) {
        ApiFailureEvent event = ctx.event();
        String category = ctx.category();
        ContractContext contracts = ctx.contracts();
        RegistryDiscoveryContext registry = ctx.registry();

        StringBuilder sb = new StringBuilder();
        sb.append(AnalysisPrompts.FIELD_GLOSSARY);
        sb.append("=== FAILURE DETAILS ===\n");
        sb.append("Category      : ").append(category).append("\n");
        sb.append("Source Service: ").append(event.getServiceA()).append("\n");
        sb.append("Target Service: ").append(event.getServiceB()).append("\n");
        sb.append("httpMethod    : ").append(event.getHttpMethod()).append("\n");
        sb.append("endpointPath  : ").append(event.getEndpoint()).append("\n");
        sb.append("HTTP Error    : ").append(event.getErrorCode()).append("\n");
        sb.append("Error Message : ").append(event.getErrorMessage()).append("\n");
        if (event.getRequestOrigin() != null) {
            sb.append("requestOrigin : ").append(event.getRequestOrigin()).append("\n");
        }
        if (event.getUpstreamOriginSent() != null) {
            sb.append("upstreamOriginSent: ").append(event.getUpstreamOriginSent()).append("\n");
        }
        if (event.getTargetServiceUrl() != null) {
            sb.append("targetServiceUrl: ").append(event.getTargetServiceUrl()).append("\n");
        }
        if (event.getRegisteredBaseUrl() != null) {
            sb.append("registeredBaseUrl: ").append(event.getRegisteredBaseUrl()).append("\n");
        }
        if (event.getDnsProbeDiscoveryUrl() != null) {
            sb.append("DNS probe found : ").append(event.getDnsProbeDiscoveryUrl()).append(" (reachable at failure time)\n");
        }
        if (event.getCorsBlockedAt() != null) {
            sb.append("corsBlockedAt   : ").append(event.getCorsBlockedAt()).append("\n");
        }
        sb.append("\n");

        appendRegistrySection(sb, event, registry);
        appendActiveRulesSection(sb, ctx.activeRulesOnRoute());

        if (contracts.hasAny()) {
            sb.append("=== REGISTERED SERVICE CONTRACTS (source of truth) ===\n");
            if (contracts.senderContract() != null) {
                sb.append("What ").append(event.getServiceA()).append(" is registered to send (canonical v1.0):\n");
                appendJson(sb, contracts.senderContract());
                sb.append("For SCHEMA_MISMATCH, prefer ACTUAL REQUEST SENT below over this registration when they differ.\n");
            }
            if (contracts.receiverContract() != null) {
                sb.append("What ").append(event.getServiceB()).append(" expects to receive:\n");
                appendJson(sb, contracts.receiverContract());
            }
            if (contracts.callerResponseContract() != null) {
                sb.append("What ").append(event.getServiceA()).append(" expects to receive in the response:\n");
                appendJson(sb, contracts.callerResponseContract());
            }
            if (contracts.providerResponseContract() != null) {
                sb.append("What ").append(event.getServiceB()).append(" is registered to respond with:\n");
                appendJson(sb, contracts.providerResponseContract());
            }
            sb.append("\n");
        }

        if ("SCHEMA_MISMATCH".equals(category)) {
            ctx.schemaDiff().appendToPrompt(sb);
        }
        if ("RESPONSE_MISMATCH".equals(category)) {
            ctx.responseDiff().appendToPrompt(sb);
        }
        if ("CORS_UPSTREAM".equals(category)) {
            ctx.corsUpstreamDiff().appendToPrompt(sb);
        }
        if ("CORS".equals(category)) {
            ctx.corsEdgeDiff().appendToPrompt(sb);
        }

        switch (category) {
            case "ROUTING" -> {
                sb.append("=== ROUTING CONTEXT ===\n");
                sb.append("Attempted URL : ").append(event.getAttemptedUrl()).append("\n");
                sb.append("The service at that URL is unreachable.\n");
                sb.append("Use SERVICE REGISTRY & DISCOVERY above — do not guess ports like 8080 unless probes confirm it.\n");
            }
            case "CORS" -> {
                sb.append("=== CORS CONTEXT (Mendr edge) ===\n");
                sb.append("Blocked Origin: ").append(event.getRequestOrigin()).append("\n");
                sb.append("Target Service: ").append(event.getServiceB()).append("\n");
                appendOriginList(sb, "mendrEdgeAllowedOrigins", ctx.mendrEdgeAllowedOrigins());
            }
            case "CORS_UPSTREAM" -> {
                sb.append("=== UPSTREAM CORS CONTEXT ===\n");
                sb.append("Blocked real Origin: ").append(event.getRequestOrigin()).append("\n");
                sb.append("Source Service: ").append(event.getServiceA()).append(" (unchanged — do not spoof)\n");
                sb.append("Target Service: ").append(event.getServiceB()).append("\n");
                sb.append("corsBlockedAt: UPSTREAM — request reached Service B; Mendr edge did NOT block.\n");
                sb.append("IMPORTANT: Failure occurred after upstream was contacted — do NOT suggest CORS_ALLOW.\n");
                appendOriginList(sb, "upstreamAllowedOrigins", ctx.upstreamAllowedOrigins());
                if (ctx.corsPolicy().allowedCallerOrigin() != null) {
                    sb.append("cors-policy allowedCallerOrigin: ").append(ctx.corsPolicy().allowedCallerOrigin()).append("\n");
                }
                appendPayload(sb, "ACTUAL REQUEST SENT", event.getRequestPayload());
                appendPayload(sb, "UPSTREAM RESPONSE SNIPPET", event.getResponsePayload());
            }
            default -> {
                appendPayload(sb, "ACTUAL REQUEST SENT", event.getRequestPayload());
                appendPayload(sb, "ACTUAL RESPONSE RECEIVED", event.getResponsePayload());
            }
        }
        return sb.toString();
    }

    private void appendActiveRulesSection(StringBuilder sb, List<ActiveRuleSummary> activeRules) {
        if (activeRules == null || activeRules.isEmpty()) return;
        sb.append("=== ACTIVE RULES ON THIS ROUTE ===\n");
        for (ActiveRuleSummary rule : activeRules) {
            sb.append("  - [").append(rule.scope()).append("] ")
              .append(rule.ruleType()).append(": ").append(rule.summary()).append("\n");
        }
        sb.append("\n");
    }

    private void appendOriginList(StringBuilder sb, String label, List<String> origins) {
        if (origins == null || origins.isEmpty()) return;
        sb.append(label).append(": ");
        appendJson(sb, origins);
    }

    private void appendRegistrySection(StringBuilder sb, ApiFailureEvent event, RegistryDiscoveryContext registry) {
        if (!registry.hasAny() && registry.allActiveServices().isEmpty()) return;

        sb.append("=== SERVICE REGISTRY & DISCOVERY (authoritative — prefer over guessing) ===\n");

        if (!registry.involvedServices().isEmpty()) {
            sb.append("Registered endpoints for involved services:\n");
            for (Map<String, Object> svc : registry.involvedServices()) {
                sb.append("  - ").append(svc.get("name"))
                  .append(" → ").append(svc.get("base_url"));
                if (svc.get("last_health_status") != null) {
                    sb.append(" [health: ").append(svc.get("last_health_status")).append("]");
                }
                if (svc.get("namespace") != null) {
                    sb.append(" (ns: ").append(svc.get("namespace")).append(")");
                }
                sb.append("\n");
            }
        }

        if (!registry.activeRoutingRules().isEmpty()) {
            sb.append("Active routing overrides:\n");
            for (Map<String, Object> rule : registry.activeRoutingRules()) {
                sb.append("  - ").append(rule.get("service_name"))
                  .append(": ").append(rule.get("original_url"))
                  .append(" → ").append(rule.get("new_url"))
                  .append(" (via ").append(rule.get("discovery_method")).append(")\n");
            }
        }

        if (!registry.recentDnsProbes().isEmpty()) {
            sb.append("Recent DNS/health probes (newest first):\n");
            for (Map<String, Object> probe : registry.recentDnsProbes()) {
                sb.append("  - ").append(probe.get("probed_url"));
                sb.append(probe.get("reachable") == Boolean.TRUE ? " ✓ REACHABLE" : " ✗ unreachable");
                if (probe.get("http_status") != null) {
                    sb.append(" HTTP ").append(probe.get("http_status"));
                }
                sb.append("\n");
            }
        }

        if (!registry.allActiveServices().isEmpty()) {
            sb.append("All active services in Mendr registry:\n");
            for (Map<String, Object> svc : registry.allActiveServices()) {
                sb.append("  - ").append(svc.get("name"))
                  .append(" → ").append(svc.get("base_url"));
                if (svc.get("last_health_status") != null) {
                    sb.append(" [").append(svc.get("last_health_status")).append("]");
                }
                sb.append("\n");
            }
        }

        if (event.getRegisteredBaseUrl() != null && event.getAttemptedUrl() != null) {
            sb.append("Hint: registered base for ").append(event.getServiceB())
              .append(" is ").append(event.getRegisteredBaseUrl())
              .append(" but gateway attempted ").append(event.getAttemptedUrl()).append("\n");
        }
        sb.append("\n");
    }

    private void appendJson(StringBuilder sb, Object obj) {
        try { sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj)).append("\n"); }
        catch (Exception e) { sb.append(obj).append("\n"); }
    }

    private void appendPayload(StringBuilder sb, String label, Map<String, Object> payload) {
        if (payload != null && !payload.isEmpty()) {
            sb.append("=== ").append(label).append(" ===\n");
            appendJson(sb, payload);
            sb.append("\n");
        }
    }

    // ── Parse & persist ───────────────────────────────────────────────────────

    private AnalysisResult parseAndSave(String rawResponse, FailureAnalysisContext ctx) {
        ApiFailureEvent event = ctx.event();
        Map<String, Object> transformationRules = new HashMap<>();
        String rootCause    = "Unable to determine root cause";
        double confidence   = 0.0;
        double modelConfidence = 0.0;
        String permanentFix = "Manual investigation required";
        boolean rulesParseFailed = false;

        try {
            String cleaned = rawResponse.trim()
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();
            JsonNode json   = objectMapper.readTree(cleaned);
            rootCause       = json.path("rootCause").asText(rootCause);
            modelConfidence = json.path("confidence").asDouble(0.0);
            confidence      = modelConfidence;
            permanentFix    = json.path("suggestedPermanentFix").asText(permanentFix);

            if (json.has("transformationRules") && !json.get("transformationRules").isNull()) {
                try {
                    transformationRules = TransformationRulesParser.parse(
                            json.get("transformationRules"), objectMapper);
                } catch (Exception rulesEx) {
                    rulesParseFailed = true;
                    log.warn("Could not normalize transformationRules for {}: {} — keeping rootCause/confidence",
                            event.getFailureId(), rulesEx.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse AI response for {}: {}", event.getFailureId(), e.getMessage());
            rootCause  = "AI analysis produced an unparseable response. Manual review required.";
            confidence = 0.0;
            modelConfidence = 0.0;
        }

        if (rulesParseFailed && transformationRules.isEmpty()) {
            confidence = Math.min(confidence, confidenceThreshold - 0.01);
        }

        transformationRules = harmonizeWithSchemaDiff(transformationRules, ctx.schemaDiff(), event.getFailureId());
        transformationRules = harmonizeWithResponseDiff(transformationRules, ctx.responseDiff(), event.getFailureId());
        transformationRules = harmonizeWithCorsUpstreamDiff(transformationRules, ctx.corsUpstreamDiff(), event.getFailureId());
        transformationRules = harmonizeWithCorsEdgeDiff(transformationRules, ctx.corsEdgeDiff(), event.getFailureId());

        enrichRoutingRules(transformationRules, event);
        enrichOriginOverrideRules(transformationRules, event);

        confidence = calibrateConfidence(confidence, modelConfidence, transformationRules, ctx);

        RuleValidator.ValidationResult validation = RuleValidator.validate(
                transformationRules, event, ctx.upstreamAllowedOrigins());
        boolean validationFailed = !validation.deployable();
        if (validationFailed) {
            confidence = Math.min(confidence, confidenceThreshold - 0.01);
            log.warn("Rule validation failed for {}: {}", event.getFailureId(), validation.reason());
        }

        boolean routingUndeployable = isRoutingWithoutTargetUrl(transformationRules, event);
        if (routingUndeployable) {
            confidence = Math.min(confidence, confidenceThreshold - 0.01);
            log.warn("Routing analysis for failure {} has no deployable suggestedNewUrl — marking below approval threshold",
                    event.getFailureId());
        }

        attachAnalysisMetadata(transformationRules, ctx, validation.reason());

        AnalysisResult result = AnalysisResult.builder()
                .failureId(event.getFailureId())
                .rootCause(rootCause)
                .confidence(confidence)
                .transformationRules(transformationRules)
                .suggestedPermanentFix(permanentFix)
                .aiModel("claude-haiku-4-5-20251001")
                .status(confidence >= confidenceThreshold && !validationFailed && !routingUndeployable
                        ? AnalysisResult.AnalysisStatus.PENDING_APPROVAL
                        : AnalysisResult.AnalysisStatus.REJECTED)
                .build();
        return analysisRepository.save(result);
    }

    private void attachAnalysisMetadata(
            Map<String, Object> rules, FailureAnalysisContext ctx, String validationReason) {
        if (rules == null) return;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("upstreamAllowedOrigins", ctx.upstreamAllowedOrigins());
        meta.put("mendrEdgeAllowedOrigins", ctx.mendrEdgeAllowedOrigins());
        if (validationReason != null) {
            meta.put("validationReason", validationReason);
        }
        rules.put("_analysisMetadata", meta);
    }

    private double calibrateConfidence(
            double effective,
            double modelConfidence,
            Map<String, Object> rules,
            FailureAnalysisContext ctx) {

        String aiType = str(rules.get("type")).toUpperCase();

        if (ctx.corsUpstreamDiff().hasDeterministicRule()) {
            String expected = "CORS_ORIGIN_OVERRIDE";
            if (!expected.equals(aiType)) {
                return Math.min(effective, 0.5);
            }
            return Math.max(effective, Math.max(modelConfidence, 0.9));
        }
        if (ctx.corsEdgeDiff().hasDeterministicRule()) {
            String expected = "CORS_ALLOW";
            if (!expected.equals(aiType)) {
                return Math.min(effective, 0.5);
            }
            return Math.max(effective, Math.max(modelConfidence, 0.9));
        }
        if (ctx.schemaDiff().hasDeterministicRule()) {
            String expected = str(ctx.schemaDiff().toTransformationRules().get("type")).toUpperCase();
            if (!expected.isBlank() && !expected.equals(aiType)) {
                return Math.min(effective, 0.5);
            }
        }
        return effective;
    }

    private Map<String, Object> harmonizeWithCorsUpstreamDiff(
            Map<String, Object> aiRules, CorsUpstreamDiffResult corsDiff, UUID failureId) {

        if (corsDiff == null || !corsDiff.hasDeterministicRule()) return aiRules;

        Map<String, Object> deterministic = corsDiff.toTransformationRules();
        String aiType = str(aiRules.get("type")).toUpperCase();
        if (!"CORS_ORIGIN_OVERRIDE".equals(aiType)) {
            log.info("CORS upstream diff override for {}: AI suggested {} → using deterministic CORS_ORIGIN_OVERRIDE",
                    failureId, aiType.isBlank() ? "none" : aiType);
            return deterministic;
        }

        Map<String, Object> merged = new LinkedHashMap<>(aiRules);
        deterministic.forEach((key, value) -> {
            if (!"type".equals(key) && merged.get(key) == null) {
                merged.put(key, value);
            }
        });
        merged.put("endpoint", EndpointNormalizer.normalize(str(merged.get("endpoint"))));
        merged.put("callerOrigin", deterministic.get("callerOrigin"));
        merged.put("outboundOrigin", deterministic.get("outboundOrigin"));
        return merged;
    }

    private Map<String, Object> harmonizeWithCorsEdgeDiff(
            Map<String, Object> aiRules, CorsEdgeDiffResult corsDiff, UUID failureId) {

        if (corsDiff == null || !corsDiff.hasDeterministicRule()) return aiRules;

        Map<String, Object> deterministic = corsDiff.toTransformationRules();
        String aiType = str(aiRules.get("type")).toUpperCase();
        if (!"CORS_ALLOW".equals(aiType)) {
            log.info("CORS edge diff override for {}: AI suggested {} → using deterministic CORS_ALLOW",
                    failureId, aiType.isBlank() ? "none" : aiType);
            return deterministic;
        }

        Map<String, Object> merged = new LinkedHashMap<>(aiRules);
        merged.put("newOrigin", deterministic.get("newOrigin"));
        if (merged.get("targetService") == null) {
            merged.put("targetService", deterministic.get("targetService"));
        }
        return merged;
    }

    private void enrichOriginOverrideRules(Map<String, Object> rules, ApiFailureEvent event) {
        if (rules == null || rules.isEmpty()) return;
        if (!"CORS_ORIGIN_OVERRIDE".equalsIgnoreCase(str(rules.get("type")))) return;

        if (str(rules.get("sourceService")).isBlank() && event.getServiceA() != null) {
            rules.put("sourceService", event.getServiceA());
        }
        if (str(rules.get("targetService")).isBlank() && event.getServiceB() != null) {
            rules.put("targetService", event.getServiceB());
        }
        if (str(rules.get("endpoint")).isBlank() && event.getEndpoint() != null) {
            rules.put("endpoint", EndpointNormalizer.normalize(event.getEndpoint()));
        } else {
            rules.put("endpoint", EndpointNormalizer.normalize(str(rules.get("endpoint"))));
        }
        if (!rules.containsKey("rewriteResponseAcao")) {
            rules.put("rewriteResponseAcao", true);
        }
    }

    /**
     * When structured schema diff finds a clear primary issue, prefer that rule over
     * a conflicting AI suggestion (e.g. FIELD_RENAME when amount is simply missing).
     */
    private Map<String, Object> harmonizeWithSchemaDiff(
            Map<String, Object> aiRules, SchemaDiffResult schemaDiff, UUID failureId) {

        if (schemaDiff == null || !schemaDiff.hasDeterministicRule()) {
            return aiRules;
        }

        Map<String, Object> deterministic = schemaDiff.toTransformationRules();
        if (deterministic.isEmpty() || isEmptyRulePayload(deterministic)) return aiRules;

        String aiType = str(aiRules.get("type")).toUpperCase();
        String expectedType = str(deterministic.get("type")).toUpperCase();

        if (!expectedType.equals(aiType)) {
            if (isEmptyRulePayload(aiRules) || isEmptyRulePayload(deterministic)) {
                return isEmptyRulePayload(aiRules) ? deterministic : aiRules;
            }
            log.info("Schema diff override for {}: AI suggested {} → using deterministic {}",
                    failureId, aiType.isBlank() ? "none" : aiType, expectedType);
            return deterministic;
        }

        // Same type — merge deterministic details if AI omitted them
        Map<String, Object> merged = new LinkedHashMap<>(aiRules);
        deterministic.forEach((key, value) -> {
            if (!"type".equals(key) && (merged.get(key) == null
                    || (merged.get(key) instanceof Map<?, ?> m && m.isEmpty()))) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private Map<String, Object> harmonizeWithResponseDiff(
            Map<String, Object> aiRules, ResponseDiffResult responseDiff, UUID failureId) {

        if (responseDiff == null || !responseDiff.hasDeterministicRule()) {
            return aiRules;
        }

        Map<String, Object> deterministic = responseDiff.toTransformationRules();
        if (deterministic.isEmpty()) return aiRules;

        String aiType = str(aiRules.get("type")).toUpperCase();
        String expectedType = str(deterministic.get("type")).toUpperCase();

        if (!expectedType.equals(aiType)) {
            log.info("Response diff override for {}: AI suggested {} → using deterministic {}",
                    failureId, aiType.isBlank() ? "none" : aiType, expectedType);
            return deterministic;
        }

        Map<String, Object> merged = new LinkedHashMap<>(aiRules);
        deterministic.forEach((key, value) -> {
            if (!"type".equals(key) && (merged.get(key) == null
                    || (merged.get(key) instanceof Map<?, ?> m && m.isEmpty()))) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private static boolean isEmptyRulePayload(Map<String, Object> rules) {
        if (rules == null || rules.isEmpty()) return true;
        String type = str(rules.get("type"));
        if (type.isBlank()) return true;
        return switch (type.toUpperCase()) {
            case "ADD_DEFAULT" -> isEmptyMap(rules.get("defaults"));
            case "FIELD_RENAME" -> isEmptyMap(rules.get("mappings"));
            case "TYPE_COERCE" -> isEmptyMap(rules.get("coercions"));
            default -> false;
        };
    }

    private static boolean isEmptyMap(Object value) {
        return !(value instanceof Map<?, ?> map) || map.isEmpty();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private void enrichRoutingRules(Map<String, Object> rules, ApiFailureEvent event) {
        if (rules == null || rules.isEmpty()) return;

        String type = str(rules.get("type"));
        boolean isRouting = "ROUTING_OVERRIDE".equalsIgnoreCase(type)
                || "ROUTING".equalsIgnoreCase(event.getFailureCategory());
        if (!isRouting) return;

        String suggested = str(rules.get("suggestedNewUrl"));
        if (!RoutingUrlResolver.isBlank(suggested)) {
            rules.put("suggestedNewUrl", RoutingUrlResolver.stripToBaseUrl(suggested));
            return;
        }

        String original = str(rules.get("originalUrl"));
        if (RoutingUrlResolver.isBlank(original)) original = event.getAttemptedUrl();
        if (RoutingUrlResolver.isBlank(original)) original = event.getTargetServiceUrl();
        if (!RoutingUrlResolver.isBlank(original)) {
            rules.put("originalUrl", RoutingUrlResolver.stripToBaseUrl(original));
        }

        if (RoutingUrlResolver.isBlank(str(rules.get("serviceName"))) && event.getServiceB() != null) {
            rules.put("serviceName", event.getServiceB());
        }

        String registered = event.getRegisteredBaseUrl();
        if (RoutingUrlResolver.isBlank(registered) && event.getServiceB() != null) {
            registered = loadRegisteredBaseUrl(event.getServiceB());
        }

        Optional<RoutingUrlResolver.ResolvedUrl> resolved = RoutingUrlResolver.resolve(
                original, registered, event.getDnsProbeDiscoveryUrl());

        if (resolved.isPresent()) {
            rules.put("suggestedNewUrl", resolved.get().baseUrl());
            rules.put("discoveryMethod", resolved.get().discoveryMethod());
            log.info("Filled suggestedNewUrl via {}: {}", resolved.get().discoveryMethod(), resolved.get().baseUrl());
        }
    }

    private boolean isRoutingWithoutTargetUrl(Map<String, Object> rules, ApiFailureEvent event) {
        if (rules == null) return "ROUTING".equalsIgnoreCase(event.getFailureCategory());
        String type = str(rules.get("type"));
        if (!"ROUTING_OVERRIDE".equalsIgnoreCase(type) && !"ROUTING".equalsIgnoreCase(event.getFailureCategory())) {
            return false;
        }
        return RoutingUrlResolver.isBlank(str(rules.get("suggestedNewUrl")));
    }

    private String loadRegisteredBaseUrl(String serviceName) {
        try {
            List<String> urls = jdbcTemplate.query(
                    "SELECT base_url FROM services WHERE name = ? AND is_active = true AND base_url IS NOT NULL LIMIT 1",
                    (rs, rowNum) -> rs.getString("base_url"),
                    serviceName);
            return urls.isEmpty() ? null : urls.getFirst();
        } catch (Exception e) {
            log.debug("Could not load registry URL for {}: {}", serviceName, e.getMessage());
            return null;
        }
    }

    // ── Publish ───────────────────────────────────────────────────────────────

    private void publishResult(AnalysisResult result, ApiFailureEvent event, FailureAnalysisContext ctx) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("analysisId",            result.getId());
        notification.put("failureId",             result.getFailureId());
        notification.put("serviceA",              event.getServiceA());
        notification.put("serviceB",              event.getServiceB());
        notification.put("endpoint",              event.getEndpoint());
        notification.put("rootCause",             result.getRootCause());
        notification.put("confidence",            result.getConfidence());
        notification.put("transformationRules",   result.getTransformationRules());
        notification.put("suggestedPermanentFix", result.getSuggestedPermanentFix());
        notification.put("status",                result.getStatus());
        notification.put("requiresApproval",      result.getStatus() == AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        notification.put("failureCategory",       event.getFailureCategory());
        notification.put("attemptedUrl",          event.getAttemptedUrl());
        notification.put("requestOrigin",         event.getRequestOrigin());
        notification.put("upstreamAllowedOrigins", ctx.upstreamAllowedOrigins());
        kafkaTemplate.send(TOPIC, result.getFailureId().toString(), notification);
    }
}
