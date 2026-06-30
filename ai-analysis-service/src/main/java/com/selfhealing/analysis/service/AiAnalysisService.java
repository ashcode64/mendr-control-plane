package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.repository.AnalysisResultRepository;
import com.selfhealing.analysis.service.context.StructuredContextAssembler;
import com.selfhealing.analysis.service.context.StructuredFailureContext;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;
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
    private final JdbcTemplate             jdbcTemplate;
    private final FailureContextEnricher   contextEnricher;

    @Value("${anthropic.confidence-threshold:0.75}")
    private double confidenceThreshold;

    private static final String TOPIC = "api.analysis.results";

    /**
     * Appended to the pointer-bearing system prompts (schema / response). The user
     * turn is a nested context object, so this stops the model from pointing into
     * that wrapper (e.g. /actualRequestPayload/...) instead of the payload root.
     */
    private static final String POINTER_GUIDANCE =
            "\n" + com.selfhealing.analysis.service.tool.AnalysisTools.POINTER_ROOT_RULE + "\n";

    // ── System Prompts ────────────────────────────────────────────────────────
    //
    // The structured JSON user turn and the per-tool input_schema descriptions now
    // carry the field semantics and per-rule constraints that the old flat-text
    // glossary / negative-example prose strained to convey. System prompts are kept
    // short: role, the single decision to make, and "call exactly one propose_* tool".

    private static final String SYS_SCHEMA = """
        You are an expert distributed systems engineer specialising in API request contract analysis.
        The request payload from the source service does not match the target's expected contract.
        Decide the ONE correct fix and call exactly one propose_* tool.
        Priority: actual has FEWER fields than receiver → propose_add_default; same count + name mismatch
        → propose_field_rename; same count + type mismatch → propose_type_coerce. Never cause data loss.
        If a deterministicFinding with hasConfidentMatch=true is present, fill in that tool's parameters.
        """ + POINTER_GUIDANCE;

    private static final String SYS_RESPONSE = """
        You are an expert distributed systems engineer specialising in API response contract analysis.
        The provider's response body does not match what the caller expects.
        Decide the ONE primary fix and call exactly one propose_response_* tool; remaining issues are
        fixed on later retries.         Missing fields → add_default; name mismatch → field_rename; type mismatch
        → type_coerce; nesting differences → wrap/unwrap.
        """ + POINTER_GUIDANCE;

    private static final String SYS_ROUTING = """
        You are an expert SRE specialising in service routing and DNS.
        A service is unreachable at the attempted URL. Call propose_routing_override with the correct base URL.
        Prefer the registry base_url or a REACHABLE DNS probe over guessing ports. suggestedNewUrl is
        scheme+host+port only, no path. Use the routing context provided; do not invent ports.
        """;

    private static final String SYS_CORS = """
        You are an expert web security engineer specialising in CORS.
        Mendr's edge blocked the caller before it reached the target (corsBlockedAt=EDGE).
        Call propose_cors_allow. newOrigin must equal the blocked requestOrigin, never a service base URL,
        never a wildcard.
        """;

    private static final String SYS_CORS_UPSTREAM = """
        You are an expert web security engineer specialising in CORS.
        The target's OWN CORS filter rejected the real caller origin AFTER Mendr forwarded the request
        (corsBlockedAt=UPSTREAM). Call propose_cors_origin_override to rewrite the outbound Origin only —
        never change source identity, never call propose_cors_allow. outboundOrigin must be one of
        upstreamAllowedOrigins; callerOrigin is the real requestOrigin, never registeredBaseUrl/targetServiceUrl.
        """;

    private static final String SYS_UNKNOWN = """
        You are an expert distributed systems engineer.
        The failure category is uncertain. You may call the read-only context tools (get_contract,
        get_service_topology, get_active_rules, get_recent_dns_probes, get_similar_past_failures) to gather
        evidence, then call exactly one propose_* tool with your best fix.
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

        StructuredFailureContext structured = StructuredContextAssembler.assemble(ctx);
        AnalysisToolResult toolResult = claudeClient.analyze(systemPrompt, structured, ctx);
        AnalysisResult result = harmonizeAndSave(toolResult, ctx);
        publishResult(result, event, ctx);
        return result;
    }

    // ── Harmonize & persist ─────────────────────────────────────────────────────

    /**
     * Consumes the typed tool result (real or mock), applies the deterministic
     * safety nets (harmonize / calibrate / enrich / validate), and persists. The
     * tool path makes the rule TYPE unambiguous, so harmonize/calibrate now act as
     * a rarely-firing backstop rather than a routine correction.
     */
    private AnalysisResult harmonizeAndSave(AnalysisToolResult toolResult, FailureAnalysisContext ctx) {
        ApiFailureEvent event = ctx.event();

        Map<String, Object> transformationRules = toolResult.transformationRules() != null
                ? new LinkedHashMap<>(toolResult.transformationRules())
                : new LinkedHashMap<>();

        // Repair pointers that leaked the context-wrapper prefix (e.g. the model
        // emitting /actualRequestPayload/tag_sent instead of /tag_sent) before any
        // scan/harmonize/validate sees them.
        int repairedPointers = RuleValidator.normalizeContextPointers(transformationRules);
        if (repairedPointers > 0) {
            log.warn("Stripped {} context-prefixed JSON pointer(s) from analysis rules for {}",
                    repairedPointers, event.getFailureId());
        }

        String rootCause = toolResult.rootCause();
        String permanentFix = toolResult.suggestedPermanentFix();
        double modelConfidence = toolResult.confidence();
        double confidence = modelConfidence;

        transformationRules = harmonizeWithSchemaDiff(transformationRules, ctx.schemaDiff(), event.getFailureId());
        transformationRules = harmonizeWithResponseDiff(transformationRules, ctx.responseDiff(), event.getFailureId());
        transformationRules = harmonizeWithCorsUpstreamDiff(transformationRules, ctx.corsUpstreamDiff(), event.getFailureId());
        transformationRules = harmonizeWithCorsEdgeDiff(transformationRules, ctx.corsEdgeDiff(), event.getFailureId());

        enrichRoutingRules(transformationRules, event);
        enrichOriginOverrideRules(transformationRules, event);

        confidence = calibrateConfidence(confidence, modelConfidence, transformationRules, ctx);

        RuleValidator.ValidationResult validation = RuleValidator.validate(
                transformationRules, event, ctx.upstreamAllowedOrigins(), event.getRequestPayload());
        boolean validationFailed = !validation.deployable();
        if (validationFailed) {
            confidence = Math.min(confidence, confidenceThreshold - 0.01);
            log.warn("Rule validation failed for {}: {}", event.getFailureId(), validation.reason());
        }

        // Deterministic effect preview: a restructure rule that cannot change the
        // failing payload is an ineffective suggestion. Surface it and keep it below
        // the approval threshold so a no-op "fix" is never auto-presented as deployable.
        RuleValidator.EffectPreview effect = RuleValidator.describeEffect(
                transformationRules, event.getRequestPayload());
        if (!effect.effective()) {
            confidence = Math.min(confidence, confidenceThreshold - 0.01);
            log.warn("Analysis suggestion for {} is a no-op against the failing payload: {}",
                    event.getFailureId(), effect.reason());
        }

        boolean routingUndeployable = isRoutingWithoutTargetUrl(transformationRules, event);
        if (routingUndeployable) {
            confidence = Math.min(confidence, confidenceThreshold - 0.01);
            log.warn("Routing analysis for failure {} has no deployable suggestedNewUrl — marking below approval threshold",
                    event.getFailureId());
        }

        AnalysisResult.AnalysisSource source = toolResult.source() == AnalysisToolResult.Source.MOCK
                ? AnalysisResult.AnalysisSource.MOCK
                : AnalysisResult.AnalysisSource.CLAUDE;

        AnalysisResult result = AnalysisResult.builder()
                .failureId(event.getFailureId())
                .rootCause(rootCause)
                .confidence(confidence)
                .transformationRules(transformationRules)
                .suggestedPermanentFix(permanentFix)
                .aiModel(toolResult.model())
                .analysisSource(source)
                .analysisMetadata(buildAnalysisMetadata(ctx, validation.reason(), effect))
                .status(confidence >= confidenceThreshold && !validationFailed && !routingUndeployable && effect.effective()
                        ? AnalysisResult.AnalysisStatus.PENDING_APPROVAL
                        : AnalysisResult.AnalysisStatus.REJECTED)
                .build();
        return analysisRepository.save(result);
    }

    /**
     * Audit-only metadata kept OFF the deployed {@code transformationRules} map so
     * it never travels into a compiled route snapshot. Lives on its own column.
     */
    private Map<String, Object> buildAnalysisMetadata(FailureAnalysisContext ctx, String validationReason,
                                                      RuleValidator.EffectPreview effect) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("upstreamAllowedOrigins", ctx.upstreamAllowedOrigins());
        meta.put("mendrEdgeAllowedOrigins", ctx.mendrEdgeAllowedOrigins());
        if (validationReason != null) {
            meta.put("validationReason", validationReason);
        }
        if (effect != null && !effect.effective()) {
            meta.put("effective", false);
            meta.put("noOpReason", effect.reason());
        }
        return meta;
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
            case "FIELD_MOVE", "RESPONSE_FIELD_MOVE" -> isEmptyList(rules.get("moves"));
            default -> false;
        };
    }

    private static boolean isEmptyMap(Object value) {
        return !(value instanceof Map<?, ?> map) || map.isEmpty();
    }

    private static boolean isEmptyList(Object value) {
        return !(value instanceof List<?> list) || list.isEmpty();
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
