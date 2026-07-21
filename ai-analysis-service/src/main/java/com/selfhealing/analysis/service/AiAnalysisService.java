package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.repository.AnalysisResultRepository;
import com.selfhealing.analysis.service.context.StructuredContextAssembler;
import com.selfhealing.analysis.service.context.StructuredFailureContext;
import com.selfhealing.analysis.service.safety.SafetyGateResult;
import com.selfhealing.analysis.service.safety.SafetyGateService;
import com.selfhealing.analysis.service.safety.SafetyScore;
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

    private final LlmAnalysisClient          llmClient;
    private final AnalysisResultRepository analysisRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JdbcTemplate             jdbcTemplate;
    private final FailureContextEnricher   contextEnricher;
    private final ErrorSignatureAssembler  errorSignatureAssembler;
    private final SafetyGateService        safetyGateService;
    private final ApprovalDeployPublisher  approvalDeployPublisher;

    /** Legacy fallback only — Safety Gate / conformal owns the approval boundary. */
    @Value("${anthropic.confidence-threshold:0.75}")
    private double confidenceThreshold;

    @Value("${mendr.conversation.diagnose-url:}")
    private String diagnoseUrl;

    @Value("${gateway.internal.api-key:}")
    private String gatewayInternalApiKey;

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
        fixed on later retries. Missing fields → add_default; name mismatch → field_rename; type mismatch
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

        ErrorSignature signature = errorSignatureAssembler.assemble(ctx);

        // Prefer LangGraph /diagnose when configured (verify+simulate path).
        // Falls back to legacy category LLM path on missing URL or diagnose failure.
        AnalysisToolResult toolResult = null;
        if (diagnoseUrl != null && !diagnoseUrl.isBlank()) {
            toolResult = tryDiagnose(signature, ctx);
        }
        if (toolResult == null) {
            String systemPrompt = switch (category) {
                case "ROUTING"           -> SYS_ROUTING;
                case "CORS"              -> SYS_CORS;
                case "CORS_UPSTREAM"     -> SYS_CORS_UPSTREAM;
                case "RESPONSE_MISMATCH" -> SYS_RESPONSE;
                case "SCHEMA_MISMATCH"   -> SYS_SCHEMA;
                default                  -> SYS_UNKNOWN;
            };
            StructuredFailureContext structured = StructuredContextAssembler.assemble(ctx);
            toolResult = llmClient.analyze(systemPrompt, structured, ctx);
        }

        AnalysisResult result = harmonizeAndSave(toolResult, ctx, signature);
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
    private AnalysisResult harmonizeAndSave(AnalysisToolResult toolResult, FailureAnalysisContext ctx,
                                            ErrorSignature signature) {
        ApiFailureEvent event = ctx.event();

        Map<String, Object> transformationRules = toolResult.transformationRules() != null
                ? new LinkedHashMap<>(toolResult.transformationRules())
                : new LinkedHashMap<>();

        boolean refuseAutoHeal = Boolean.TRUE.equals(transformationRules.remove("_refuseAutoHeal"))
                || Boolean.TRUE.equals(transformationRules.remove("_owner_action_required"));
        Object lagReasonMeta = transformationRules.remove("_lagReason");

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
            log.warn("Rule validation failed for {}: {}", event.getFailureId(), validation.reason());
        }

        // Deterministic effect preview: a restructure rule that cannot change the
        // failing payload is an ineffective suggestion. Surface it — SafetyGate
        // rejects non-effective suggestions without the old confidenceThreshold clamp.
        RuleValidator.EffectPreview effect = RuleValidator.describeEffect(
                transformationRules, event.getRequestPayload());
        if (!effect.effective()) {
            log.warn("Analysis suggestion for {} is a no-op against the failing payload: {}",
                    event.getFailureId(), effect.reason());
        }

        boolean routingUndeployable = isRoutingWithoutTargetUrl(transformationRules, event);
        if (routingUndeployable) {
            log.warn("Routing analysis for failure {} has no deployable suggestedNewUrl",
                    event.getFailureId());
        }

        if (refuseAutoHeal) {
            // Keep confidence informative but queue for human review (PENDING_APPROVAL).
            log.info("refuseAutoHeal / owner_action_required for {} — queuing PENDING_APPROVAL with HITL banners",
                    event.getFailureId());
        }

        AnalysisResult.AnalysisSource source = switch (toolResult.source()) {
            case MOCK -> AnalysisResult.AnalysisSource.MOCK;
            case GEMINI -> AnalysisResult.AnalysisSource.GEMINI;
            case CLAUDE -> AnalysisResult.AnalysisSource.CLAUDE;
        };

        Map<String, Object> meta = buildAnalysisMetadata(ctx, validation.reason(), effect, signature);
        if (refuseAutoHeal) {
            meta.put("refuseAutoHeal", true);
            meta.put("owner_action_required", true);
            if (lagReasonMeta != null) meta.put("lagReason", lagReasonMeta);
        }
        Object simulationMeta = transformationRules.remove("_simulation");
        Object verificationMeta = transformationRules.remove("_verification");
        Object lagEvidenceMeta = transformationRules.remove("_lagEvidence");
        Object metamorphicMeta = transformationRules.remove("_metamorphic");
        Object ddminMeta = transformationRules.remove("_ddmin");
        Object banditMeta = transformationRules.remove("_bandit");
        if (simulationMeta != null) meta.put("simulation", simulationMeta);
        if (verificationMeta != null) meta.put("verification", verificationMeta);
        if (lagEvidenceMeta != null) meta.put("lagEvidence", lagEvidenceMeta);
        if (metamorphicMeta != null) meta.put("metamorphic", metamorphicMeta);
        if (ddminMeta != null) meta.put("ddmin", ddminMeta);
        if (banditMeta != null) meta.put("bandit", banditMeta);
        if (signature != null) {
            meta.put("spec_trust", signature.specTrust());
        }

        Double metamorphicPassRate = extractMetamorphicPassRate(metamorphicMeta);
        double deterministicAgreement = (!validationFailed && effect.effective() && !routingUndeployable)
                ? Math.max(modelConfidence, 0.85)
                : 0.35;
        boolean hitlReview = refuseAutoHeal
                || "HITL_REVIEW".equals(toolResult.ruleType());
        SafetyScore safetyScore = safetyGateService.buildScore(
                modelConfidence,
                deterministicAgreement,
                metamorphicPassRate,
                signature != null ? signature.specTrust() : null,
                0.5);
        SafetyGateResult gate = safetyGateService.evaluate(
                refuseAutoHeal, validationFailed, routingUndeployable, effect.effective(),
                hitlReview, safetyScore);
        gate.mergeInto(meta);

        AnalysisResult.AnalysisStatus status = gate.status();

        AnalysisResult result = AnalysisResult.builder()
                .failureId(event.getFailureId())
                .rootCause(rootCause)
                .confidence(confidence)
                .transformationRules(transformationRules)
                .suggestedPermanentFix(permanentFix)
                .aiModel(toolResult.model())
                .analysisSource(source)
                .analysisMetadata(meta)
                .status(status)
                .build();
        result = analysisRepository.save(result);

        // Plan 8.0 step 5: conformal accept + auto-apply → same deploy path as human Approve.
        if (status == AnalysisResult.AnalysisStatus.APPROVED) {
            approvalDeployPublisher.publishApproved(result, "safety-gate-auto-apply");
        }
        return result;
    }

    /**
     * HITL refuse / owner_action flags always land in {@code PENDING_APPROVAL} so humans
     * can review in the queue — even when validation, effect, or confidence would reject.
     *
     * @deprecated Prefer {@link SafetyGateService#evaluate}; retained for unit tests and
     *             callers that lack a full SafetyScore.
     */
    @Deprecated
    static AnalysisResult.AnalysisStatus resolveApprovalStatus(
            boolean refuseAutoHeal,
            boolean validationFailed,
            boolean routingUndeployable,
            boolean effectEffective,
            double confidence,
            double confidenceThreshold) {
        if (refuseAutoHeal) {
            return AnalysisResult.AnalysisStatus.PENDING_APPROVAL;
        }
        boolean approveEligible = !validationFailed && !routingUndeployable && effectEffective
                && confidence >= confidenceThreshold;
        return approveEligible
                ? AnalysisResult.AnalysisStatus.PENDING_APPROVAL
                : AnalysisResult.AnalysisStatus.REJECTED;
    }

    @SuppressWarnings("unchecked")
    static Double extractMetamorphicPassRate(Object metamorphicMeta) {
        if (!(metamorphicMeta instanceof Map<?, ?> m)) return null;
        Object rate = m.get("passRate");
        if (rate instanceof Number n) return n.doubleValue();
        Object passed = m.get("passed");
        Object total = m.get("total");
        if (passed instanceof Number p && total instanceof Number t && t.doubleValue() > 0) {
            return p.doubleValue() / t.doubleValue();
        }
        return null;
    }

    /**
     * Audit-only metadata kept OFF the deployed {@code transformationRules} map so
     * it never travels into a compiled route snapshot. Lives on its own column.
     */
    private Map<String, Object> buildAnalysisMetadata(FailureAnalysisContext ctx, String validationReason,
                                                      RuleValidator.EffectPreview effect,
                                                      ErrorSignature signature) {
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
        if (signature != null) {
            meta.put("errorSignature", signature.toMap());
            meta.put("sketchHint", signature.toSketchHint());
        }
        return meta;
    }

    /**
     * Call conversation-engine POST /diagnose with the ErrorSignature.
     * Returns null on any failure so the legacy LLM path remains the backstop.
     */
    @SuppressWarnings("unchecked")
    private AnalysisToolResult tryDiagnose(ErrorSignature signature, FailureAnalysisContext ctx) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> sigMap = new LinkedHashMap<>(signature.toMap());
            if (ctx.event().getCorrelationId() != null) {
                sigMap.put("correlationId", ctx.event().getCorrelationId());
            }
            if (ctx.event().getServiceA() != null) {
                sigMap.put("sourceService", ctx.event().getServiceA());
            }
            body.put("errorSignature", sigMap);

            List<Map<String, Object>> cases = new ArrayList<>();
            if (ctx.event().getRequestPayload() != null && !ctx.event().getRequestPayload().isEmpty()) {
                cases.add(Map.of("input", ctx.event().getRequestPayload()));
            }
            if ("RESPONSE_MISMATCH".equalsIgnoreCase(ctx.category())) {
                Map<String, Object> resp = extractResponseForCases(ctx.event().getResponsePayload());
                if (!resp.isEmpty()) {
                    cases.add(Map.of("input", resp));
                }
            }
            if (cases.isEmpty()) {
                cases.add(Map.of("input", Map.of()));
            }
            body.put("cases", cases);

            boolean deterministic = (ctx.schemaDiff() != null && ctx.schemaDiff().hasDeterministicRule())
                    || (ctx.responseDiff() != null && ctx.responseDiff().hasDeterministicRule())
                    || (ctx.corsUpstreamDiff() != null && ctx.corsUpstreamDiff().hasDeterministicRule())
                    || (ctx.corsEdgeDiff() != null && ctx.corsEdgeDiff().hasDeterministicRule());
            boolean multiHop = isMultiHop(ctx);
            body.put("complexity", Map.of(
                    "deterministicDiff", deterministic,
                    "category", ctx.category() != null ? ctx.category() : "UNKNOWN",
                    "multiHop", multiHop));

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(body);
            java.net.http.HttpRequest.Builder req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(diagnoseUrl.endsWith("/diagnose")
                            ? diagnoseUrl : diagnoseUrl.replaceAll("/$", "") + "/diagnose"))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json));
            String internalKey = gatewayInternalApiKey;
            if (internalKey == null || internalKey.isBlank()) {
                internalKey = System.getenv("GATEWAY_INTERNAL_API_KEY");
            }
            if (internalKey != null && !internalKey.isBlank()) {
                req.header("X-Internal-Api-Key", internalKey);
            }

            java.net.http.HttpResponse<String> resp = client.send(req.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("Diagnose HTTP {} for {}: {}", resp.statusCode(),
                        ctx.event().getFailureId(), resp.body());
                return null;
            }
            Map<String, Object> parsed = mapper.readValue(resp.body(), Map.class);
            AnalysisToolResult interpreted = interpretDiagnoseResponse(parsed);
            if (interpreted == null) {
                log.info("Diagnose returned non-ready without HITL refuse for {} — status={}",
                        ctx.event().getFailureId(), parsed.getOrDefault("status", ""));
            } else if (interpreted.transformationRules() != null
                    && Boolean.TRUE.equals(interpreted.transformationRules().get("_refuseAutoHeal"))) {
                log.info("Diagnose HITL refuse for {} — queue PENDING_APPROVAL (status={})",
                        ctx.event().getFailureId(), parsed.getOrDefault("status", ""));
            }
            return interpreted;
        } catch (Exception e) {
            log.warn("Diagnose call failed for {}: {}", ctx.event().getFailureId(), e.getMessage());
            return null;
        }
    }

    /**
     * Interpret conversation-engine {@code /diagnose} JSON.
     * Returns {@code null} only when the response is not ready <em>and</em> carries no
     * HITL refuse flags (legacy LLM remains the backstop). When refuse/owner_action is set,
     * always return a tool result — even with no program — so humans review instead of
     * LLM inventing a victim heal.
     */
    @SuppressWarnings("unchecked")
    static AnalysisToolResult interpretDiagnoseResponse(Map<String, Object> parsed) {
        if (parsed == null || parsed.isEmpty()) return null;

        boolean refuseAutoHeal = diagnoseRefuse(parsed);
        String status = String.valueOf(parsed.getOrDefault("status", ""));
        Object program = parsed.get("program");
        boolean ready = program instanceof Map<?, ?> && "ready".equals(status);

        if (!ready && !refuseAutoHeal) {
            return null;
        }

        Map<String, Object> rules = new LinkedHashMap<>();
        String ruleType;
        if (program instanceof Map<?, ?> prog) {
            rules.putAll((Map<String, Object>) prog);
            rules.put("type", "DSL_PROGRAM");
            ruleType = "DSL_PROGRAM";
        } else {
            // HITL-only: no deployable program — still surfaces banners + PENDING_APPROVAL.
            rules.put("type", "HITL_REVIEW");
            ruleType = "HITL_REVIEW";
        }

        String rationale = parsed.get("rationale") != null
                ? parsed.get("rationale").toString()
                : "Diagnosed via LangGraph verify/simulate loop";
        double conf = parsed.get("confidence") instanceof Number n ? n.doubleValue() : (ready ? 0.85 : 0.4);
        String model = String.valueOf(parsed.getOrDefault("model", "conversation-engine"));

        if (refuseAutoHeal) {
            String lag = parsed.get("lagReason") != null ? parsed.get("lagReason").toString() : null;
            if (lag == null && parsed.get("diagnosis") instanceof Map<?, ?> diag
                    && diag.get("lagReason") != null) {
                lag = diag.get("lagReason").toString();
            }
            rationale = "HITL required — refuseAutoHeal (upstream lag). "
                    + (lag != null ? lag + " " : "")
                    + rationale;
            rules.put("_refuseAutoHeal", true);
            rules.put("_owner_action_required", true);
            if (lag != null) rules.put("_lagReason", lag);
        }

        if (parsed.get("simulation") != null) {
            rules.put("_simulation", parsed.get("simulation"));
        }
        if (parsed.get("verification") != null) {
            rules.put("_verification", parsed.get("verification"));
        }
        if (parsed.get("metamorphic") != null) {
            rules.put("_metamorphic", parsed.get("metamorphic"));
        }
        if (parsed.get("ddmin") != null) {
            rules.put("_ddmin", parsed.get("ddmin"));
        }
        if (parsed.get("bandit") != null) {
            rules.put("_bandit", parsed.get("bandit"));
        }
        Object diagnosis = parsed.get("diagnosis");
        if (diagnosis instanceof Map<?, ?> dmap) {
            if (dmap.get("lagEvidence") != null) {
                rules.put("_lagEvidence", dmap.get("lagEvidence"));
            }
        }

        AnalysisToolResult.Source source = model.toLowerCase().contains("gemini")
                ? AnalysisToolResult.Source.GEMINI
                : AnalysisToolResult.Source.CLAUDE;
        return new AnalysisToolResult(
                source,
                model,
                ruleType,
                rules,
                rationale,
                conf,
                refuseAutoHeal
                        ? "HITL required: upstream root cause — do not auto-heal downstream victim"
                        : "Approve verified MendrScript program");
    }

    static boolean diagnoseRefuse(Map<String, Object> parsed) {
        if (parsed == null) return false;
        if (Boolean.TRUE.equals(parsed.get("refuseAutoHeal"))
                || Boolean.TRUE.equals(parsed.get("owner_action_required"))) {
            return true;
        }
        if (parsed.get("diagnosis") instanceof Map<?, ?> diag) {
            return Boolean.TRUE.equals(diag.get("refuseAutoHeal"))
                    || Boolean.TRUE.equals(diag.get("owner_action_required"));
        }
        return false;
    }

    /** Multi-hop when topology shows inbound or outbound neighbors beyond the failing edge. */
    private static boolean isMultiHop(FailureAnalysisContext ctx) {
        if (ctx.topology() == null) return false;
        var topo = ctx.topology();
        int neighbors = 0;
        if (topo.sourceOutboundCalls() != null) neighbors += topo.sourceOutboundCalls().size();
        if (topo.targetInboundCallers() != null) neighbors += topo.targetInboundCallers().size();
        return neighbors > 1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractResponseForCases(Map<String, Object> responsePayload) {
        if (responsePayload == null || responsePayload.isEmpty()) return Map.of();
        Object raw = responsePayload.get("raw");
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return responsePayload;
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
            // Verified MendrScript from /diagnose is an intentional alternative to
            // the classic FIELD_RENAME/TYPE_COERCE map — do not penalize it.
            if ("DSL_PROGRAM".equals(aiType)) {
                return Math.max(effective, Math.max(modelConfidence, 0.85));
            }
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
     * Verified MendrScript programs ({@code DSL_PROGRAM}) from /diagnose are preserved —
     * they already passed verify_program + simulate_transform.
     */
    private Map<String, Object> harmonizeWithSchemaDiff(
            Map<String, Object> aiRules, SchemaDiffResult schemaDiff, UUID failureId) {

        if (schemaDiff == null || !schemaDiff.hasDeterministicRule()) {
            return aiRules;
        }
        if (isDslProgram(aiRules)) {
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
        if (isDslProgram(aiRules)) {
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

    private static boolean isDslProgram(Map<String, Object> rules) {
        if (rules == null || rules.isEmpty()) return false;
        if (!"DSL_PROGRAM".equalsIgnoreCase(str(rules.get("type")))) return false;
        Object ops = rules.get("ops");
        return ops instanceof List<?> list && !list.isEmpty();
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
