package com.selfhealing.analysis.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.FailureContextEnricher;
import com.selfhealing.analysis.service.TopologyQueryService;
import com.selfhealing.analysis.service.context.TopologyContext;
import com.selfhealing.analysis.service.embed.SignatureEmbedder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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
    private final TopologyQueryService topologyQueryService;
    private final MendrScriptGatewayClient mendrScriptGatewayClient;
    private final com.selfhealing.analysis.service.embed.PrecedentsEmbedClient precedentsEmbedClient;
    private final com.selfhealing.analysis.service.ddmin.DdminOracleService ddminOracleService;
    private final com.selfhealing.analysis.service.bandit.BanditService banditService;
    private final com.selfhealing.analysis.service.ace.AcePlaybookService acePlaybookService;
    private final com.selfhealing.analysis.service.heuristics.RepairHeuristicsService repairHeuristicsService;
    private final com.selfhealing.analysis.service.skills.SkillLibraryService skillLibraryService;
    private final com.selfhealing.analysis.service.metamemory.MetaMemoryService metaMemoryService;
    private final com.selfhealing.analysis.service.evolvemem.EvolveMemService evolveMemService;
    private final com.selfhealing.analysis.service.gepa.GepaCompileService gepaCompileService;

    @Value("${mendr.precedents.lag-window-minutes:15}")
    private int lagWindowMinutes;

    @Value("${mendr.precedents.vector-top-k:8}")
    private int vectorTopK;

    @Value("${mendr.precedents.cross-tenant-champions:false}")
    private boolean crossTenantChampions;

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
            toolDef("get_blast_radius",
                    "TOPOLOGY (deterministic). If this service fails, who is transitively affected — "
                            + "backward reachability over current topology edges with a cycle guard + depth cap. "
                            + "Returns {affected:[{service, depth}]}. Ground truth; never invent affected services.",
                    Map.of(
                            "service", strProp(),
                            "maxDepth", Map.of("type", "integer", "description", "Optional hop cap (default 10)")),
                    List.of("service")),
            toolDef("get_root_cause_candidates",
                    "TOPOLOGY (deterministic). This service is failing — what did it (transitively) depend on? "
                            + "Forward reachability, ranked so confirmed causal-cascade dependencies outrank merely "
                            + "structurally-reachable ones. Also returns an ENUMERATED closed set of real dependency "
                            + "paths (each with pathIndex + edgeIds). For RCA, SELECT a pathIndex — do not invent a path.",
                    Map.of(
                            "service", strProp(),
                            "maxDepth", Map.of("type", "integer", "description", "Optional hop cap (default 10)")),
                    List.of("service")),
            toolDef("get_dependency_path",
                    "TOPOLOGY (deterministic). Enumerate real forward dependency paths from one service to another "
                            + "(each with pathIndex, ordered services, and edgeIds). Empty when no path exists — "
                            + "a claimed connection with no path here is structurally impossible.",
                    Map.of(
                            "fromService", strProp(),
                            "toService", strProp(),
                            "maxDepth", Map.of("type", "integer", "description", "Optional hop cap (default 10)")),
                    List.of("fromService", "toService")),
            toolDef("get_dependency_cycles",
                    "TOPOLOGY (deterministic). Detect circular dependencies (A->B->C->A) as a first-class "
                            + "architectural finding. Returns {cycles:[{services:[...]}]}.",
                    Map.of("maxDepth", Map.of("type", "integer", "description", "Optional hop cap (default 10)")),
                    List.of()),
            toolDef("get_topology_drift",
                    "TOPOLOGY (deterministic). Reconcile declared (manifest/OpenAPI) vs observed (traffic) edges. "
                            + "Returns OBSERVED_UNDECLARED shadow dependencies (security-shaped) and "
                            + "DECLARED_UNOBSERVED possibly-dead edges. No args.",
                    Map.of(),
                    List.of()),
            toolDef("verify_rca_claims",
                    "TOPOLOGY (symbolic verifier — Postgres as solver). Check each claimed edge / node / causal edge "
                            + "against the CURRENT topology. A claim is supported ONLY if the concrete row exists now. "
                            + "Call this to fail-closed on any RCA sentence before presenting it. "
                            + "claims: list of {type?, edgeId?, nodeId?, sourceService?, targetService?, endpoint?}.",
                    Map.of("claims", Map.of("type", "array",
                            "description", "Claims to verify",
                            "items", Map.of("type", "object"))),
                    List.of("claims")),
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
                    List.of("sourceService", "targetService", "endpoint")),
            toolDef("get_error_signature",
                    "Fetch the canonical ErrorSignature for a failure (template_id, json_path, change_type, "
                            + "contract_ref, spec_trust) plus the matching error_taxonomy entry when present. "
                            + "Prefer this over raw errorMessage when diagnosing.",
                    Map.of("failureId", strProp()),
                    List.of("failureId")),
            toolDef("get_precedents",
                    "Retrieve similar past resolved ErrorSignatures / transformations as few-shot precedents. "
                            + "Hybrid GraphRAG: pgvector top-k then TopologyContext / taxonomy causal / "
                            + "drift_signatures enrich; may set owner_action_required when an upstream "
                            + "cause is inside the lag window.",
                    Map.of(
                            "failureId", Map.of("type", "string", "description", "Optional failure id"),
                            "sourceService", strProp(),
                            "targetService", strProp(),
                            "endpoint", strProp(),
                            "changeType", Map.of("type", "string", "description", "e.g. TYPE_COERCE"),
                            "jsonPath", Map.of("type", "string", "description", "RFC6901 pointer"),
                            "includeNegatives", Map.of("type", "boolean",
                                    "description", "Include REJECTED/FAILURE precedents (default false)")),
                    List.of()),
            toolDef("verify_program",
                    "Statically verify a MendrScript program (closed-opcode AST {schemaVersion, ops:[...]}). "
                            + "Returns {valid, errors, warnings, signature}. ALWAYS call before proposing a program: "
                            + "it enforces the opcode allowlist, arg types, protected-path scan, dataflow ordering, "
                            + "value-op post-conditions and structured predicates — the SAME checks the deploy path runs.",
                    Map.of("program", objProp("The MendrScript AST: {schemaVersion, ops:[...]}")),
                    List.of("program")),
            toolDef("simulate_transform",
                    "Run a verified MendrScript program against example inputs using the reference executor and "
                            + "return the before/after for each case (plus fail-closed faults as counterexamples). "
                            + "Use this to SHOW the user what a program actually does before they approve it.",
                    Map.of(
                            "program", objProp("The MendrScript AST: {schemaVersion, ops:[...]}"),
                            "cases", Map.of("type", "array",
                                    "description", "List of {input, expected?} example objects",
                                    "items", Map.of("type", "object"))),
                    List.of("program", "cases")),
            toolDef("verify_properties",
                    "Offline metamorphic / property checks on a MendrScript program (idempotent rename, "
                            + "non-target byte-identity, protected-path safety, coerce preservation, allowed surface). "
                            + "Never live-probes upstreams. Call after verify_program; feed passRate into Safety Gate.",
                    Map.of(
                            "program", objProp("The MendrScript AST: {schemaVersion, ops:[...]}"),
                            "inputs", Map.of("type", "array",
                                    "description", "Optional seed payloads for metamorphic fuzz (offline only)",
                                    "items", Map.of("type", "object"))),
                    List.of("program")),
            toolDef("minimize_program",
                    "Deterministic remediation minimization (Rust ddmin+eqsat+prove_minimal, Java re-verify). "
                            + "Call AFTER verify/simulate/metamorphic succeed and BEFORE presenting a program for approval. "
                            + "Returns the minimal equivalent AST (or the draft if re-verify fails / no improvement).",
                    Map.of(
                            "program", objProp("Verified MendrScript AST {schemaVersion, ops:[...]}"),
                            "cases", Map.of("type", "array",
                                    "description", "Simulation cases [{input, expected?}]",
                                    "items", Map.of("type", "object")),
                            "triggeringPayload", Map.of("type", "object",
                                    "description", "Exact incident payload (twin gate 2 for schema-gated coerce removal)"),
                            "specTrust", Map.of("type", "number",
                                    "description", "0..1 contract trust (twin gate 1)"),
                            "allowedOpcodes", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "Optional sketch opcode allowlist"),
                            "declaredFieldTypes", Map.of("type", "object",
                                    "description", "JSON-pointer → declared OpenAPI type for twin-gated coerce removal"),
                            "unresolvablePaths", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "oneOf/anyOf / polymorphic pointers — necessity never drops ops on these")),
                    List.of("program")),
            toolDef("localize_fields",
                    "Delta-debug (ddmin) multi-field drift with bifurcated oracle: "
                            + "SCHEMA_MISMATCH→offline simulate; RFC 9110 safe methods "
                            + "(GET/HEAD/OPTIONS[/TRACE])→live with X-Mendr-Diagnostic-Probe; "
                            + "mutating POST/PUT/PATCH/DELETE→ABORT to HITL (config cannot enable). "
                            + "Returns minimal field set + path. Unresolved (oneOf/anyOf) is never coerced.",
                    Map.of(
                            "category", Map.of("type", "string", "description", "SCHEMA_MISMATCH / RESPONSE_MISMATCH / …"),
                            "httpMethod", Map.of("type", "string", "description", "GET, POST, …"),
                            "jsonPath", Map.of("type", "string", "description", "Precise RFC pointer when known"),
                            "fields", Map.of("type", "array", "description", "Candidate drifted fields",
                                    "items", Map.of("type", "object")),
                            "payload", Map.of("type", "object", "description", "Failing request/response payload"),
                            "targetService", strProp(),
                            "endpoint", strProp(),
                            "baseUrl", Map.of("type", "string", "description", "Optional upstream base URL for Path B")),
                    List.of("category")),
            toolDef("select_bandit_arms",
                    "True REx: Thompson-sample ≤3 strategy categories for ambiguous cases only. "
                            + "Opens an in-memory local session (literal program arms registered later). "
                            + "Global credit stays async via bandit_pending_credit. Deterministic Synthesis should skip this.",
                    Map.of(
                            "ambiguous", Map.of("type", "boolean", "description", "True when agent-loop / UNKNOWN / multi-hop"),
                            "preferredCategories", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "Optional preferred strategy categories"),
                            "sessionId", Map.of("type", "string", "description", "Optional incident session id")),
                    List.of()),
            toolDef("get_ace_playbook",
                    "Fetch ACE evolving playbook bullets (SUCCESS strategies + FAILURE warn-offs) for few-shot guidance.",
                    Map.of(
                            "category", Map.of("type", "string", "description", "Optional failure category filter"),
                            "changeType", Map.of("type", "string", "description", "Optional change_type filter")),
                    List.of()),
            toolDef("get_repair_heuristics",
                    "Fetch topology-scoped repair heuristics (ExpeL) for the failing route. "
                            + "Requires source/target/endpoint to build topology_scope; returns SUCCESS tips + FAILURE warn-offs.",
                    Map.of(
                            "sourceService", strProp(),
                            "targetService", strProp(),
                            "endpoint", strProp(),
                            "category", Map.of("type", "string", "description", "Optional category filter"),
                            "changeType", Map.of("type", "string", "description", "Optional change_type filter")),
                    List.of()),
            toolDef("match_skill",
                    "LILO skill fast-path: match a RegressionHarness-gated structural macro to the current sketch "
                            + "(change_type + allowed opcodes). Returns an instantiable MendrScript program when matched.",
                    Map.of(
                            "changeType", Map.of("type", "string", "description", "ErrorSignature change_type"),
                            "category", Map.of("type", "string", "description", "Optional failure category"),
                            "allowedOpcodes", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "Opcodes implied by the sketch hole"),
                            "jsonPath", Map.of("type", "string", "description", "Target JSON path to instantiate onto")),
                    List.of()),
            toolDef("get_meta_memory",
                    "Fetch MetaMemory abstract rules distilled from TRUSTED precedent clusters (Semantic Memory).",
                    Map.of(
                            "category", Map.of("type", "string", "description", "Optional failure category filter"),
                            "changeType", Map.of("type", "string", "description", "Optional change_type filter")),
                    List.of()),
            toolDef("register_local_program",
                    "True REx: register a literal MendrScript program as a local arm in the incident session. "
                            + "bandit_category is coerced to the Thompson-sampled set or the branch is aborted.",
                    Map.of(
                            "sessionId", strProp(),
                            "banditCategory", Map.of("type", "string", "description", "Category tag for this program"),
                            "program", objProp("MendrScript AST {schemaVersion, ops:[...]}")),
                    List.of("sessionId", "program")),
            toolDef("observe_local_bandit",
                    "True REx: update local Beta for a program arm after critics (verify/simulate). Never updates global bandit_state.",
                    Map.of(
                            "sessionId", strProp(),
                            "localArmId", strProp(),
                            "success", Map.of("type", "boolean", "description", "True when critics passed")),
                    List.of("sessionId", "localArmId", "success")),
            toolDef("pick_local_bandit",
                    "True REx: Thompson-sample among registered local program arms; returns the winning program + category.",
                    Map.of("sessionId", strProp()),
                    List.of("sessionId")),
            toolDef("get_retrieval_config",
                    "EvolveMem: fetch the ACTIVE versioned retrieval config (topK, minScore, maxDistance, decay).",
                    Map.of(),
                    List.of()),
            toolDef("get_compiled_prompt",
                    "GEPA/MIPRO: fetch ACTIVE compiled propose addendum (scrubbed corpus only; never raw api_failures).",
                    Map.of(
                            "promptKind", Map.of("type", "string",
                                    "description", "propose_addendum (default) or propose_system")),
                    List.of()));

    /** Dispatch a context-tool call; returns a JSON-serializable result map. */
    public Object execute(String toolName, Map<String, Object> input) {
        try {
            return switch (toolName) {
                case "get_contract" -> getContract(s(input, "service"), s(input, "endpoint"), s(input, "direction"));
                case "get_service_topology" -> getTopology(s(input, "service"));
                case "get_blast_radius" -> topologyQueryService.blastRadius(
                        s(input, "service"), intArg(input, "maxDepth", TopologyQueryService.DEFAULT_MAX_DEPTH));
                case "get_root_cause_candidates" -> getRootCauseCandidates(input);
                case "get_dependency_path" -> topologyQueryService.dependencyPaths(
                        s(input, "fromService"), s(input, "toService"),
                        intArg(input, "maxDepth", TopologyQueryService.DEFAULT_MAX_DEPTH),
                        TopologyQueryService.DEFAULT_MAX_PATHS);
                case "get_dependency_cycles" -> topologyQueryService.dependencyCycles(
                        intArg(input, "maxDepth", TopologyQueryService.DEFAULT_MAX_DEPTH));
                case "get_topology_drift" -> topologyQueryService.topologyDrift();
                case "verify_rca_claims" -> verifyRcaClaims(input);
                case "get_active_rules" -> getActiveRules(
                        s(input, "sourceService"), s(input, "targetService"), s(input, "endpoint"));
                case "get_recent_dns_probes" -> getRecentProbes(s(input, "service"));
                case "get_similar_past_failures" -> getSimilarFailures(
                        s(input, "sourceService"), s(input, "targetService"), s(input, "endpoint"));
                case "get_error_signature" -> getErrorSignature(s(input, "failureId"));
                case "get_precedents" -> getPrecedents(input);
                case "verify_program" -> mendrScriptGatewayClient.verify(input == null ? null : input.get("program"));
                case "simulate_transform" -> mendrScriptGatewayClient.simulate(Map.of(
                        "program", input == null ? Map.of() : input.getOrDefault("program", Map.of()),
                        "cases", input == null ? List.of() : input.getOrDefault("cases", List.of())));
                case "verify_properties" -> mendrScriptGatewayClient.verifyProperties(Map.of(
                        "program", input == null ? Map.of() : input.getOrDefault("program", Map.of()),
                        "inputs", input == null ? List.of() : input.getOrDefault("inputs", List.of())));
                case "minimize_program" -> minimizeProgram(input);
                case "localize_fields" -> localizeFields(input);
                case "select_bandit_arms" -> selectBanditArms(input);
                case "register_local_program" -> registerLocalProgram(input);
                case "observe_local_bandit" -> observeLocalBandit(input);
                case "pick_local_bandit" -> pickLocalBandit(input);
                case "get_retrieval_config" -> getRetrievalConfig(input);
                case "get_compiled_prompt" -> getCompiledPrompt(input);
                case "get_ace_playbook" -> getAcePlaybook(input);
                case "get_repair_heuristics" -> getRepairHeuristics(input);
                case "match_skill" -> matchSkill(input);
                case "get_meta_memory" -> getMetaMemory(input);
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
            SELECT example_payload, inferred_schema, schema_source, spec_trust, version, description
            FROM service_contracts
            WHERE service_name = ? AND endpoint = ? AND direction = ? AND is_active = true
            ORDER BY created_at DESC LIMIT 5
            """, service, endpoint, direction == null ? "REQUEST" : direction.toUpperCase());
        if (rows.isEmpty()) return Map.of("found", false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("examples", rows.stream().map(r -> {
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("version", String.valueOf(r.get("version")));
            ex.put("description", String.valueOf(r.get("description")));
            ex.put("payload", r.get("example_payload"));
            ex.put("inferred_schema", r.get("inferred_schema"));
            ex.put("schema_source", r.get("schema_source"));
            ex.put("spec_trust", r.get("spec_trust"));
            return ex;
        }).toList());
        return out;
    }

    private Object getTopology(String service) {
        TopologyContext topo = enricher.loadTopology(service, null, null);
        return topo == null ? Map.of("found", false) : topo;
    }

    /** Root-cause candidates + the enumerated closed set of real dependency paths to select from. */
    private Object getRootCauseCandidates(Map<String, Object> input) {
        String service = s(input, "service");
        int maxDepth = intArg(input, "maxDepth", TopologyQueryService.DEFAULT_MAX_DEPTH);
        Map<String, Object> candidates = new LinkedHashMap<>(
                topologyQueryService.rootCauseCandidates(service, maxDepth));
        Map<String, Object> paths = topologyQueryService.rootCausePaths(
                service, maxDepth, TopologyQueryService.DEFAULT_MAX_PATHS);
        candidates.put("paths", paths.get("paths"));
        candidates.put("pathCount", paths.get("count"));
        candidates.put("note", paths.get("note"));
        return candidates;
    }

    @SuppressWarnings("unchecked")
    private Object verifyRcaClaims(Map<String, Object> input) {
        List<Map<String, Object>> claims = new ArrayList<>();
        Object raw = input == null ? null : input.get("claims");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    claims.add((Map<String, Object>) m);
                }
            }
        }
        return topologyQueryService.verifyClaims(claims);
    }

    private static int intArg(Map<String, Object> input, String key, int def) {
        Object v = input == null ? null : input.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
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

    @SuppressWarnings("unchecked")
    private Object getErrorSignature(String failureId) {
        if (failureId == null || failureId.isBlank()) {
            return Map.of("found", false, "error", "failureId required");
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT analysis_metadata FROM analysis_results
                WHERE failure_id = ?::uuid
                ORDER BY created_at DESC LIMIT 1
                """, failureId);
            Map<String, Object> signature = null;
            if (!rows.isEmpty() && rows.get(0).get("analysis_metadata") != null) {
                Object metaRaw = rows.get(0).get("analysis_metadata");
                Map<String, Object> meta = metaRaw instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : objectMapper.readValue(metaRaw.toString(), Map.class);
                Object sig = meta.get("errorSignature");
                if (sig instanceof Map<?, ?> sm) {
                    signature = (Map<String, Object>) sm;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", signature != null);
            if (signature != null) {
                out.put("errorSignature", signature);
                Object templateId = signature.get("template_id");
                if (templateId != null && !templateId.toString().isBlank()) {
                    out.put("taxonomy", fetchTaxonomy(templateId.toString()));
                } else if (signature.get("change_type") != null) {
                    out.put("taxonomy", fetchTaxonomyByChangeType(signature.get("change_type").toString()));
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("get_error_signature failed: {}", e.getMessage());
            return Map.of("found", false, "error", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Object getPrecedents(Map<String, Object> input) {
        String source = s(input, "sourceService");
        String target = s(input, "targetService");
        String endpoint = s(input, "endpoint");
        String changeType = s(input, "changeType");
        String jsonPath = s(input, "jsonPath");
        String failureId = s(input, "failureId");
        boolean includeNegatives = Boolean.TRUE.equals(input.get("includeNegatives"));

        Map<String, Object> signature = new LinkedHashMap<>();
        if (failureId != null) {
            Object sigLookup = getErrorSignature(failureId);
            if (sigLookup instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("found"))) {
                Object es = m.get("errorSignature");
                if (es instanceof Map<?, ?> sm) {
                    signature.putAll((Map<String, Object>) sm);
                }
            }
            // contract_coords.service is the TARGET (serviceB); load the caller from api_failures.
            Map<String, String> route = loadFailureRoute(failureId);
            if (source == null) source = route.get("source");
            if (target == null) target = route.get("target");
            if (endpoint == null) {
                endpoint = route.get("endpoint");
                if (endpoint == null && signature.get("contract_coords") instanceof Map<?, ?> c) {
                    endpoint = strOrNull(c.get("endpoint"));
                }
            }
            if (target == null && signature.get("contract_coords") instanceof Map<?, ?> c) {
                target = strOrNull(c.get("service"));
            }
            if (changeType == null) changeType = strOrNull(signature.get("change_type"));
            if (jsonPath == null) jsonPath = strOrNull(signature.get("json_path"));
        }
        if (changeType != null) signature.putIfAbsent("change_type", changeType);
        if (jsonPath != null) signature.putIfAbsent("json_path", jsonPath);
        if (endpoint != null || target != null) {
            Map<String, Object> coords = signature.get("contract_coords") instanceof Map<?, ?> existing
                    ? new LinkedHashMap<>((Map<String, Object>) existing)
                    : new LinkedHashMap<>();
            if (target != null) coords.putIfAbsent("service", target);
            if (endpoint != null) coords.putIfAbsent("endpoint", endpoint);
            signature.put("contract_coords", coords);
        }

        try {
            List<Map<String, Object>> vectorHits = queryVectorPrecedents(signature, includeNegatives);
            String retrieval;
            List<Map<String, Object>> precedents;
            if (!vectorHits.isEmpty()) {
                precedents = vectorHits;
                retrieval = "hybrid-graphrag";
            } else {
                precedents = querySqlPrecedents(source, target, endpoint, changeType);
                retrieval = "sql+topology";
            }

            TopologyContext topo = null;
            if (source != null || target != null) {
                topo = enricher.loadTopology(
                        source != null ? source : target,
                        target,
                        endpoint);
            }

            List<Map<String, Object>> neighbors = flattenTopology(topo, target);
            List<Map<String, Object>> causalHints = fetchCausalHints(
                    strOrNull(signature.get("template_id")), changeType);
            List<Map<String, Object>> globalDrift = fetchGlobalDrift(endpoint, jsonPath, changeType);

            LagRefuse lag = evaluateLagRefuse(source, changeType, jsonPath);

            // Apply spec_trust ranking prior when vector path already scored; SQL path re-rank lightly
            if ("sql+topology".equals(retrieval)) {
                precedents = rankSqlWithSpecTrust(precedents);
            }

            java.util.UUID tenantId = null;
            try {
                tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
            } catch (Exception ignored) {
            }
            var retrievalCfg = evolveMemService.activeConfig(tenantId);
            if ("hybrid-graphrag".equals(retrieval)) {
                precedents = evolveMemService.applyRetrievalPolicy(precedents, retrievalCfg);
            } else if (precedents.size() > retrievalCfg.topK()) {
                precedents = new ArrayList<>(precedents.subList(0, retrievalCfg.topK()));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("precedents", precedents);
            out.put("graphNeighbors", neighbors);
            out.put("causalHints", causalHints);
            out.put("globalDrift", globalDrift);
            out.put("owner_action_required", lag.ownerActionRequired());
            out.put("refuseAutoHeal", lag.refuseAutoHeal());
            out.put("lagReason", lag.reason());
            out.put("lagEvidence", lag.evidence());
            out.put("retrieval", retrieval);
            out.put("retrievalConfig", retrievalCfg.toMap());
            return out;
        } catch (Exception e) {
            log.debug("get_precedents failed: {}", e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("precedents", List.of());
            err.put("error", e.getMessage());
            err.put("owner_action_required", false);
            err.put("refuseAutoHeal", false);
            err.put("lagEvidence", List.of());
            err.put("retrieval", "error");
            return err;
        }
    }

    private Map<String, String> loadFailureRoute(String failureId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT service_a, service_b, endpoint FROM api_failures WHERE id = ?::uuid LIMIT 1
                """, failureId);
            if (rows.isEmpty()) return Map.of();
            Map<String, Object> r = rows.get(0);
            Map<String, String> out = new LinkedHashMap<>();
            if (r.get("service_a") != null) out.put("source", r.get("service_a").toString());
            if (r.get("service_b") != null) out.put("target", r.get("service_b").toString());
            if (r.get("endpoint") != null) out.put("endpoint", r.get("endpoint").toString());
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> queryVectorPrecedents(
            Map<String, Object> signature, boolean includeNegatives) {
        try {
            String vectorLit = SignatureEmbedder.toVectorLiteral(precedentsEmbedClient.embed(signature));
            String qualityFilter = includeNegatives
                    ? "quality IN ('TRUSTED','CANDIDATE','REJECTED')"
                    : "quality IN ('TRUSTED','CANDIDATE')";
            // Cross-tenant champions OFF by default: never pull anonymized (tenant_id IS NULL) rows.
            String tenantClause = crossTenantChampions
                    ? ""
                    : "AND tenant_id IS NOT NULL";
            java.util.UUID tenantId = null;
            try {
                tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
            } catch (Exception ignored) {
            }
            var cfg = evolveMemService.activeConfig(tenantId);
            // Fetch a wider pool so decay / min_score can still fill topK
            int fetchLimit = Math.min(64, Math.max(cfg.topK() * 3, vectorTopK));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, analysis_id, failure_id, category, change_type, json_path, template_id,
                       contract_ref, program, outcome, quality, spec_trust,
                       source_service, target_service, endpoint, approved_at, verified_at, created_at,
                       (embedding <=> ?::vector) AS distance
                FROM error_precedents
                WHERE %s
                %s
                AND archived_at IS NULL
                ORDER BY
                  CASE quality WHEN 'TRUSTED' THEN 0 WHEN 'CANDIDATE' THEN 1 ELSE 2 END,
                  CASE outcome WHEN 'FAILURE' THEN 1 ELSE 0 END,
                  (embedding <=> ?::vector)
                    / GREATEST(0.25, COALESCE(spec_trust, 0.5))
                LIMIT ?
                """.formatted(qualityFilter, tenantClause),
                    vectorLit, vectorLit, fetchLimit);

            List<Map<String, Object>> scored = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                double distance = row.get("distance") instanceof Number n ? n.doubleValue() : 1.0;
                double trust = row.get("spec_trust") instanceof Number n ? n.doubleValue() : 0.5;
                double qualityWeight = switch (String.valueOf(row.get("quality"))) {
                    case "TRUSTED" -> 1.0;
                    case "CANDIDATE" -> 0.75;
                    default -> 0.4;
                };
                double score = (1.0 - distance) * (0.5 + 0.5 * trust) * qualityWeight;
                Map<String, Object> item = new LinkedHashMap<>(row);
                item.put("score", score);
                item.put("transformation_rules", row.get("program"));
                scored.add(item);
            }
            scored.sort(Comparator.comparingDouble(
                    (Map<String, Object> m) -> m.get("score") instanceof Number n ? n.doubleValue() : 0.0
            ).reversed());
            return scored;
        } catch (Exception e) {
            log.debug("vector precedents unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> querySqlPrecedents(
            String source, String target, String endpoint, String changeType) {
        List<Map<String, Object>> precedents = jdbcTemplate.queryForList("""
            SELECT ar.id AS analysis_id, ar.failure_id, ar.confidence, ar.status,
                   ar.transformation_rules, ar.analysis_metadata, ar.created_at,
                   af.service_a, af.service_b, af.endpoint, af.error_type
            FROM analysis_results ar
            JOIN api_failures af ON af.id = ar.failure_id
            WHERE (? IS NULL OR af.service_a = ?)
              AND (? IS NULL OR af.service_b = ?)
              AND (? IS NULL OR af.endpoint = ?)
              AND ar.status IN ('PENDING_APPROVAL', 'APPROVED')
            ORDER BY ar.created_at DESC
            LIMIT 10
            """, source, source, target, target, endpoint, endpoint);

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : precedents) {
            if (changeType != null && !changeType.isBlank()) {
                Object meta = row.get("analysis_metadata");
                String metaStr = meta == null ? "" : meta.toString();
                Object rules = row.get("transformation_rules");
                String rulesStr = rules == null ? "" : rules.toString();
                if (!metaStr.contains(changeType) && !rulesStr.contains(changeType)) {
                    continue;
                }
            }
            filtered.add(row);
        }
        return filtered;
    }

    private List<Map<String, Object>> rankSqlWithSpecTrust(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            double trust = 0.5;
            Object meta = row.get("analysis_metadata");
            if (meta != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(meta.toString(), Map.class);
                    Object es = parsed.get("errorSignature");
                    if (es instanceof Map<?, ?> em && em.get("spec_trust") instanceof Number n) {
                        trust = n.doubleValue();
                    }
                } catch (Exception ignored) {
                    // leave default
                }
            }
            copy.put("spec_trust", trust);
            copy.put("score", 0.5 + 0.5 * trust);
            out.add(copy);
        }
        out.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> m.get("score") instanceof Number n ? n.doubleValue() : 0.0
        ).reversed());
        return out;
    }

    private List<Map<String, Object>> flattenTopology(TopologyContext topo, String targetFallback) {
        if (topo != null && !topo.isEmpty()) {
            List<Map<String, Object>> neighbors = new ArrayList<>();
            if (topo.failingCall() != null) {
                neighbors.add(edgeMap(topo.failingCall(), "failing"));
            }
            for (TopologyContext.Edge e : nullSafe(topo.sourceOutboundCalls())) {
                neighbors.add(edgeMap(e, "source_outbound"));
            }
            for (TopologyContext.Edge e : nullSafe(topo.targetInboundCallers())) {
                neighbors.add(edgeMap(e, "target_inbound"));
            }
            return neighbors;
        }
        if (targetFallback == null) return List.of();
        try {
            return jdbcTemplate.queryForList("""
                SELECT source_service, target_service, endpoint, http_method, description
                FROM service_routes
                WHERE (source_service = ? OR target_service = ?) AND is_active = true
                ORDER BY updated_at DESC NULLS LAST
                LIMIT 15
                """, targetFallback, targetFallback);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<TopologyContext.Edge> nullSafe(List<TopologyContext.Edge> edges) {
        return edges == null ? List.of() : edges;
    }

    private static Map<String, Object> edgeMap(TopologyContext.Edge e, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("source_service", e.sourceService());
        m.put("target_service", e.targetService());
        m.put("endpoint", e.endpoint());
        m.put("http_method", e.httpMethod());
        m.put("description", e.description());
        return m;
    }

    private List<Map<String, Object>> fetchCausalHints(String templateId, String changeType) {
        try {
            String key = templateId != null ? templateId : changeType;
            if (key == null || key.isBlank()) return List.of();
            return jdbcTemplate.queryForList("""
                SELECT t.template_id, t.meaning, t.causes_template_id, t.suggested_opcode, t.severity,
                       c.meaning AS causes_meaning, c.suggested_opcode AS causes_opcode
                FROM error_taxonomy t
                LEFT JOIN error_taxonomy c ON c.template_id = t.causes_template_id
                WHERE t.template_id = ? OR t.suggested_opcode = ?
                LIMIT 3
                """, key, key.toLowerCase().replace('_', '-'));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchGlobalDrift(String endpoint, String jsonPath, String changeType) {
        if (endpoint == null && jsonPath == null && changeType == null) return List.of();
        try {
            String normalizedChange = changeType == null ? null
                    : changeType.toLowerCase().replace('_', '-');
            // Map TYPE_COERCE / FIELD_RENAME etc. toward drift corpus vocab where possible
            String corpusChange = null;
            if (changeType != null) {
                String u = changeType.toLowerCase();
                if (u.contains("rename")) corpusChange = "rename";
                else if (u.contains("move")) corpusChange = "move";
                else if (u.contains("coerce") || u.contains("retype")) corpusChange = "retype";
                else if (u.contains("default") || u.contains("add")) corpusChange = "scale";
                else corpusChange = normalizedChange;
            }
            return jdbcTemplate.queryForList("""
                SELECT provider, endpoint_pattern, json_pointer, change_type,
                       suggested_dsl, occurrence_count, tenant_count, last_seen_at
                FROM drift_signatures
                WHERE (? IS NULL OR endpoint_pattern = ? OR ? LIKE REPLACE(endpoint_pattern, '*', '%'))
                  AND (? IS NULL OR json_pointer = ?)
                  AND (
                    ? IS NULL
                    OR LOWER(change_type) = LOWER(?)
                    OR LOWER(change_type) = LOWER(?)
                    OR LOWER(REPLACE(change_type, '_', '-')) = LOWER(?)
                  )
                ORDER BY occurrence_count DESC, last_seen_at DESC
                LIMIT 5
                """,
                    endpoint, endpoint, endpoint,
                    jsonPath, jsonPath,
                    changeType, changeType, corpusChange, normalizedChange);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Object minimizeProgram(Map<String, Object> input) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (input == null) input = Map.of();
        body.put("program", input.getOrDefault("program", Map.of()));
        body.put("cases", input.getOrDefault("cases", List.of()));
        if (input.get("triggeringPayload") != null) {
            body.put("triggeringPayload", input.get("triggeringPayload"));
        }
        if (input.get("specTrust") instanceof Number n) {
            body.put("specTrust", n.doubleValue());
        }
        if (input.get("allowedOpcodes") instanceof List<?> list) {
            body.put("allowedOpcodes", list);
        }
            if (input.get("declaredFieldTypes") instanceof Map<?, ?> types) {
            Map<String, String> declared = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : types.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    declared.put(e.getKey().toString(), e.getValue().toString());
                }
            }
            if (!declared.isEmpty()) {
                body.put("declaredFieldTypes", declared);
            }
        }
        if (input.get("unresolvablePaths") instanceof List<?> list) {
            List<String> paths = new ArrayList<>();
            for (Object o : list) {
                if (o != null) paths.add(o.toString());
            }
            if (!paths.isEmpty()) {
                body.put("unresolvablePaths", paths);
            }
        }
        Map<String, Object> result = mendrScriptGatewayClient.minimize(body);
        // Best-effort preference-pair capture when op count shrinks (DPO training deferred).
        Object origCount = result.get("originalOpCount");
        Object finalCount = result.get("finalOpCount");
        boolean sizesDiffer = origCount instanceof Number o && finalCount instanceof Number f
                && o.intValue() > f.intValue();
        if (Boolean.TRUE.equals(result.get("minimized")) && sizesDiffer && result.get("preferencePair") != null) {
            try {
                Object pair = result.get("preferencePair");
                Map<?, ?> pairMap = pair instanceof Map<?, ?> m ? m : Map.of();
                java.util.UUID tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
                jdbcTemplate.update("""
                    INSERT INTO minimization_preference_pairs
                        (tenant_id, chosen_program, rejected_program, layers, meta)
                    VALUES (?::uuid, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                    """,
                        tenantId,
                        objectMapper.writeValueAsString(pairMap.get("chosen")),
                        objectMapper.writeValueAsString(pairMap.get("rejected")),
                        objectMapper.writeValueAsString(result.getOrDefault("layersApplied", List.of())),
                        objectMapper.writeValueAsString(Map.of(
                                "originalOpCount", result.get("originalOpCount"),
                                "finalOpCount", result.get("finalOpCount"),
                                "engine", String.valueOf(result.get("engine")))));
            } catch (Exception e) {
                log.debug("preference pair capture skipped: {}", e.getMessage());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object selectBanditArms(Map<String, Object> input) {
        boolean ambiguous = input != null && Boolean.TRUE.equals(input.get("ambiguous"));
        if (!banditService.shouldEngage(ambiguous)) {
            return Map.of("engaged", false, "arms", List.of(), "reason", "not_ambiguous_or_disabled");
        }
        List<String> preferred = new ArrayList<>();
        if (input != null && input.get("preferredCategories") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) preferred.add(o.toString());
            }
        }
        String sessionHint = s(input, "sessionId");
        java.util.UUID tenantId = null;
        try {
            tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
        } catch (Exception ignored) {
        }
        return banditService.openSession(tenantId, preferred, sessionHint);
    }

    @SuppressWarnings("unchecked")
    private Object registerLocalProgram(Map<String, Object> input) {
        String sessionId = s(input, "sessionId");
        String category = s(input, "banditCategory");
        if (category == null) category = s(input, "category");
        Object programRaw = input == null ? null : input.get("program");
        Map<String, Object> program = Map.of();
        if (programRaw instanceof Map<?, ?> m) {
            program = new LinkedHashMap<>((Map<String, Object>) m);
        }
        return banditService.registerLocalProgram(sessionId, category, program);
    }

    private Object observeLocalBandit(Map<String, Object> input) {
        String sessionId = s(input, "sessionId");
        String localArmId = s(input, "localArmId");
        boolean success = input != null && Boolean.TRUE.equals(input.get("success"));
        return banditService.observeLocal(sessionId, localArmId, success);
    }

    private Object pickLocalBandit(Map<String, Object> input) {
        return banditService.pickLocal(s(input, "sessionId"));
    }

    private Object getRetrievalConfig(Map<String, Object> input) {
        java.util.UUID tenantId = null;
        try {
            tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
        } catch (Exception ignored) {
        }
        var cfg = evolveMemService.activeConfig(tenantId);
        Map<String, Object> out = new LinkedHashMap<>(cfg.toMap());
        out.put("active", true);
        return out;
    }

    private Object getCompiledPrompt(Map<String, Object> input) {
        String kind = s(input, "promptKind");
        java.util.UUID tenantId = null;
        try {
            tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
        } catch (Exception ignored) {
        }
        return gepaCompileService.fetchActive(tenantId, kind);
    }

    private Object getAcePlaybook(Map<String, Object> input) {
        String category = s(input, "category");
        String changeType = s(input, "changeType");
        java.util.UUID tenantId = null;
        try {
            tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
        } catch (Exception ignored) {
        }
        List<Map<String, Object>> bullets = acePlaybookService.fetchActive(tenantId, category, changeType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bullets", bullets);
        out.put("count", bullets.size());
        List<Map<String, Object>> success = bullets.stream()
                .filter(b -> "SUCCESS".equals(String.valueOf(b.get("outcome"))))
                .toList();
        List<Map<String, Object>> failure = bullets.stream()
                .filter(b -> "FAILURE".equals(String.valueOf(b.get("outcome")))
                        || "WARN".equals(String.valueOf(b.get("outcome"))))
                .toList();
        out.put("successBullets", success);
        out.put("failureWarnOffs", failure);
        return out;
    }

    private Object getRepairHeuristics(Map<String, Object> input) {
        String source = s(input, "sourceService");
        String target = s(input, "targetService");
        String endpoint = s(input, "endpoint");
        String category = s(input, "category");
        String changeType = s(input, "changeType");
        String scope = com.selfhealing.analysis.service.heuristics.TopologyScope.of(source, target, endpoint);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topologyScope", scope);
        if (scope == null) {
            out.put("heuristics", List.of());
            out.put("error", "topology_scope required (need source/target/endpoint)");
            return out;
        }
        java.util.UUID tenantId = null;
        try {
            tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
        } catch (Exception ignored) {
        }
        List<Map<String, Object>> heuristics = repairHeuristicsService.fetchForTopology(
                tenantId, scope, category, changeType);
        out.put("heuristics", heuristics);
        out.put("count", heuristics.size());
        out.put("successHeuristics", heuristics.stream()
                .filter(h -> "SUCCESS".equals(String.valueOf(h.get("outcome"))))
                .toList());
        out.put("failureWarnOffs", heuristics.stream()
                .filter(h -> {
                    String o = String.valueOf(h.get("outcome"));
                    return "FAILURE".equals(o) || "WARN".equals(o);
                })
                .toList());
        return out;
    }


    private Object matchSkill(Map<String, Object> input) {
        String changeType = s(input, "changeType");
        String category = s(input, "category");
        String jsonPath = s(input, "jsonPath");
        List<String> allowed = new ArrayList<>();
        if (input != null && input.get("allowedOpcodes") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) allowed.add(o.toString());
            }
        }
        java.util.UUID tenantId = null;
        try {
            tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
        } catch (Exception ignored) {
        }
        return skillLibraryService.match(tenantId, changeType, category, allowed, jsonPath);
    }

    private Object getMetaMemory(Map<String, Object> input) {
        String category = s(input, "category");
        String changeType = s(input, "changeType");
        java.util.UUID tenantId = null;
        try {
            tenantId = com.selfhealing.analysis.tenant.TenantContext.currentOrDefault();
        } catch (Exception ignored) {
        }
        List<Map<String, Object>> rules = metaMemoryService.fetchActive(tenantId, category, changeType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules", rules);
        out.put("count", rules.size());
        return out;
    }

    private LagRefuse evaluateLagRefuse(
            String sourceService, String changeType, String jsonPath) {
        if (sourceService == null || sourceService.isBlank()) {
            return LagRefuse.none();
        }
        try {
            List<String> upstream = new ArrayList<>();
            // Callers of the failing source (true upstream). TopologyContext.targetInboundCallers
            // are callers of the *target* service, so they must not be used here.
            List<Map<String, Object>> inbound = jdbcTemplate.queryForList("""
                SELECT DISTINCT source_service
                FROM service_routes
                WHERE target_service = ? AND is_active = true
                LIMIT 25
                """, sourceService);
            for (Map<String, Object> r : inbound) {
                if (r.get("source_service") != null) {
                    upstream.add(r.get("source_service").toString());
                }
            }
            upstream = upstream.stream().distinct().toList();
            if (upstream.isEmpty()) return LagRefuse.none();

            StringBuilder placeholders = new StringBuilder();
            List<Object> params = new ArrayList<>();
            for (int i = 0; i < upstream.size(); i++) {
                if (i > 0) placeholders.append(',');
                placeholders.append('?');
                params.add(upstream.get(i));
            }
            for (String u : upstream) params.add(u);
            params.add(lagWindowMinutes);

            String sql = """
                SELECT id, service_a, service_b, endpoint, error_type, error_message,
                       template_id, detected_at
                FROM api_failures
                WHERE (service_a IN (%s) OR service_b IN (%s))
                  AND detected_at >= NOW() - make_interval(mins => ?)
                ORDER BY detected_at DESC
                LIMIT 12
                """.formatted(placeholders, placeholders);

            List<Map<String, Object>> hits;
            try {
                hits = jdbcTemplate.queryForList(sql, params.toArray());
            } catch (Exception missingCol) {
                // template_id column may be absent on older schemas
                sql = """
                    SELECT id, service_a, service_b, endpoint, error_type, error_message, detected_at
                    FROM api_failures
                    WHERE (service_a IN (%s) OR service_b IN (%s))
                      AND detected_at >= NOW() - make_interval(mins => ?)
                    ORDER BY detected_at DESC
                    LIMIT 12
                    """.formatted(placeholders, placeholders);
                hits = jdbcTemplate.queryForList(sql, params.toArray());
            }

            // Also surface recent contract / template churn as deploy-lag evidence
            List<Map<String, Object>> deployEvidence = new ArrayList<>();
            try {
                StringBuilder upPh = new StringBuilder();
                List<Object> upParams = new ArrayList<>();
                for (int i = 0; i < upstream.size(); i++) {
                    if (i > 0) upPh.append(',');
                    upPh.append('?');
                    upParams.add(upstream.get(i));
                }
                upParams.add(lagWindowMinutes);
                List<Map<String, Object>> contractChurn = jdbcTemplate.queryForList("""
                    SELECT service_name, endpoint, schema_source, version, created_at
                    FROM service_contracts
                    WHERE service_name IN (%s)
                      AND created_at >= NOW() - make_interval(mins => ?)
                    ORDER BY created_at DESC
                    LIMIT 5
                    """.formatted(upPh), upParams.toArray());
                for (Map<String, Object> c : contractChurn) {
                    Map<String, Object> ev = new LinkedHashMap<>(c);
                    ev.put("evidenceType", "contract_churn");
                    deployEvidence.add(ev);
                }
            } catch (Exception ignored) {
                // best-effort
            }

            if (hits.isEmpty() && deployEvidence.isEmpty()) return LagRefuse.none();

            boolean hasChangeType = changeType != null && !changeType.isBlank();
            boolean hasJsonPath = jsonPath != null && !jsonPath.isBlank();

            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                String et = hit.get("error_type") == null ? "" : hit.get("error_type").toString();
                String msg = hit.get("error_message") == null ? "" : hit.get("error_message").toString();
                String tid = hit.get("template_id") == null ? "" : hit.get("template_id").toString();
                boolean typeMatch = !hasChangeType
                        || et.toUpperCase().contains(changeType.toUpperCase())
                        || msg.toUpperCase().contains(changeType.toUpperCase())
                        || tid.toUpperCase().contains(changeType.toUpperCase());
                boolean pathMatch = !hasJsonPath
                        || msg.contains(jsonPath)
                        || et.contains(jsonPath)
                        || tid.contains(jsonPath);
                if (typeMatch && pathMatch) {
                    Map<String, Object> enriched = new LinkedHashMap<>(hit);
                    enriched.put("evidenceType", "upstream_failure");
                    filtered.add(enriched);
                }
            }
            // Broaden to all lag hits only when neither signature filter was provided.
            if (filtered.isEmpty() && !hasChangeType && !hasJsonPath) {
                for (Map<String, Object> hit : hits) {
                    Map<String, Object> enriched = new LinkedHashMap<>(hit);
                    enriched.put("evidenceType", "upstream_failure");
                    filtered.add(enriched);
                }
            }
            filtered.addAll(deployEvidence);
            if (filtered.isEmpty()) return LagRefuse.none();

            Map<String, Object> top = filtered.get(0);
            String reason = "Upstream evidence " + top.getOrDefault("id", top.get("service_name"))
                    + " within " + lagWindowMinutes + "m lag window"
                    + (jsonPath != null ? " (query path " + jsonPath + ")" : "")
                    + (top.get("template_id") != null ? " template_id=" + top.get("template_id") : "")
                    + ("contract_churn".equals(top.get("evidenceType")) ? " [contract churn]" : "");
            return new LagRefuse(true, true, reason, filtered);
        } catch (Exception e) {
            log.debug("lag refuse check failed: {}", e.getMessage());
            return LagRefuse.none();
        }
    }

    private record LagRefuse(
            boolean ownerActionRequired,
            boolean refuseAutoHeal,
            String reason,
            List<Map<String, Object>> evidence) {
        static LagRefuse none() {
            return new LagRefuse(false, false, null, List.of());
        }
    }

    private Object fetchTaxonomy(String templateId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT template_id, meaning, root_causes, suggested_opcode, severity, layer
                FROM error_taxonomy WHERE template_id = ? LIMIT 1
                """, templateId);
            return rows.isEmpty() ? Map.of("found", false) : Map.of("found", true, "entry", rows.get(0));
        } catch (Exception e) {
            return Map.of("found", false);
        }
    }

    private Object fetchTaxonomyByChangeType(String changeType) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT template_id, meaning, root_causes, suggested_opcode, severity, layer
                FROM error_taxonomy
                WHERE suggested_opcode = ? OR template_id = ?
                LIMIT 1
                """, changeType.toLowerCase().replace('_', '-'), changeType);
            return rows.isEmpty() ? Map.of("found", false) : Map.of("found", true, "entry", rows.get(0));
        } catch (Exception e) {
            return Map.of("found", false);
        }
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static String s(Map<String, Object> input, String key) {
        Object v = input == null ? null : input.get(key);
        return v == null ? null : v.toString();
    }

    private static Map<String, Object> strProp() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> objProp(String description) {
        return Map.of("type", "object", "description", description);
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
