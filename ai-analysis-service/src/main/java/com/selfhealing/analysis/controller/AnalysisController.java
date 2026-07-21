package com.selfhealing.analysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.repository.AnalysisResultRepository;
import com.selfhealing.analysis.service.ApprovalDeployPublisher;
import com.selfhealing.analysis.service.tool.MendrScriptGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisResultRepository analysisRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MendrScriptGatewayClient mendrScriptGatewayClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApprovalDeployPublisher approvalDeployPublisher;

    @GetMapping
    public ResponseEntity<Page<AnalysisResult>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(analysisRepository.findAllByOrderByAnalyzedAtDesc(PageRequest.of(page, size)));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<AnalysisResult>> getPending() {
        return ResponseEntity.ok(analysisRepository.findByStatus(AnalysisResult.AnalysisStatus.PENDING_APPROVAL));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnalysisResult> getById(@PathVariable UUID id) {
        return analysisRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Stage a chat-synthesized MendrScript program against this analysis for approval.
     *
     * <p>The conversation engine only proposes; this is the single place a verified
     * program is attached to an analysis so the EXISTING approve flow can deploy it. We
     * re-verify server-side via the authoritative gateway verifier (defense-in-depth —
     * never trust the client's "valid"), record the verbatim AST + signature +
     * verification proof + before/after simulation in {@code transform_programs} for
     * audit, and set the analysis's {@code transformationRules} to a {@code DSL_PROGRAM}
     * the rule-engine knows how to deploy. This endpoint NEVER deploys — approval stays a
     * separate human step.
     */
    @PostMapping("/{id}/program")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> attachProgram(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {

        return analysisRepository.findById(id).map(result -> {
            Object program = body.get("program");
            if (!(program instanceof Map<?, ?> progMap)
                    || !(progMap.get("ops") instanceof List<?> ops) || ops.isEmpty()) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "program must be a MendrScript AST with a non-empty ops[]");
                return ResponseEntity.badRequest().body(err);
            }

            // Authoritative re-verification — the SAME verifier the deploy path runs.
            Map<String, Object> verification = mendrScriptGatewayClient.verify(program);
            if (!Boolean.TRUE.equals(verification.get("valid"))) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("error", "program failed verification");
                resp.put("verification", verification);
                return ResponseEntity.unprocessableEntity().body(resp);
            }

            String schemaVersion = String.valueOf(
                    ((Map<String, Object>) progMap).getOrDefault("schemaVersion", "mendrscript/v1"));
            Object signature = verification.get("signature");
            Object simulation = body.get("simulation");
            String conversationId = str(body.get("conversationId"));
            String model = str(body.get("model"));

            // Provenance / audit row: verbatim AST + signature + verification proof + diffs.
            UUID programId = UUID.randomUUID();
            try {
                // Stamp tenant_id from the bound context (falls back to default tenant on
                // single-tenant deployments). Required under RLS: the column default alone
                // would stamp the default tenant and be rejected by the WITH CHECK policy on
                // a non-default connection.
                jdbcTemplate.update("""
                    INSERT INTO transform_programs
                        (id, tenant_id, analysis_id, supersedes_analysis_id, conversation_id, model, schema_version,
                         ast, signature, verification, example_diffs, status, created_by, created_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                            'PROPOSED', ?, NOW())
                    """,
                    programId.toString(),
                    com.selfhealing.analysis.tenant.TenantContext.currentOrDefault().toString(),
                    id.toString(), id.toString(), conversationId, model, schemaVersion,
                    json(program), json(signature), json(verification), json(simulation),
                    conversationId == null ? "conversation-engine" : conversationId);
            } catch (Exception e) {
                log.warn("transform_programs audit insert failed (continuing): {}", e.getMessage());
            }

            // The deployable rule the rule-engine understands: top-level {type, schemaVersion, ops}.
            Map<String, Object> rules = new LinkedHashMap<>();
            rules.put("type", "DSL_PROGRAM");
            rules.put("schemaVersion", schemaVersion);
            rules.put("ops", ops);
            rules.put("signature", signature);
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("transformProgramId", programId.toString());
            provenance.put("conversationId", conversationId);
            provenance.put("model", model);
            provenance.put("verified", true);
            rules.put("provenance", provenance);

            result.setTransformationRules(rules);
            result.setStatus(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
            analysisRepository.save(result);

            log.info("Staged verified DSL_PROGRAM ({} ops) on analysis {} [program {}]",
                    ops.size(), id, programId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("message", "Verified program staged for approval");
            resp.put("analysisId", id);
            resp.put("transformProgramId", programId);
            resp.put("verification", verification);
            return ResponseEntity.ok(resp);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Approve a transformation - triggers rule deployment */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {

        return analysisRepository.findById(id).map(result -> {
            // Idempotent: already APPROVED (e.g. SafetyGate auto-apply) — do not re-publish Kafka.
            if (result.getStatus() == AnalysisResult.AnalysisStatus.APPROVED) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", "Already approved — deploy was not re-triggered");
                response.put("analysisId", id);
                response.put("alreadyApproved", true);
                return ResponseEntity.ok(response);
            }

            result.setStatus(AnalysisResult.AnalysisStatus.APPROVED);
            analysisRepository.save(result);
            String actedBy = body != null ? body.getOrDefault("approvedBy", "dashboard-user") : "dashboard-user";
            approvalDeployPublisher.publishApproved(result, actedBy);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Transformation rule approved and deployment triggered");
            response.put("analysisId", id);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Reject a transformation suggestion */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {

        return analysisRepository.findById(id).map(result -> {
            result.setStatus(AnalysisResult.AnalysisStatus.REJECTED);
            analysisRepository.save(result);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Transformation suggestion rejected");
            response.put("analysisId", id);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long total = analysisRepository.count();
        long pending = analysisRepository.findByStatus(AnalysisResult.AnalysisStatus.PENDING_APPROVAL).size();
        long approved = analysisRepository.findByStatus(AnalysisResult.AnalysisStatus.APPROVED).size();
        long rejected = analysisRepository.findByStatus(AnalysisResult.AnalysisStatus.REJECTED).size();

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("pending", pending);
        stats.put("approved", approved);
        stats.put("rejected", rejected);
        return ResponseEntity.ok(stats);
    }

    private String json(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
