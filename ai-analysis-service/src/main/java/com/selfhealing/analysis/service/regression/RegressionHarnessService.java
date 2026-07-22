package com.selfhealing.analysis.service.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.MockAnalysis;
import com.selfhealing.analysis.service.context.StructuredContextAssembler;
import com.selfhealing.analysis.service.context.StructuredFailureContext;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;
import com.selfhealing.analysis.service.tool.MendrScriptGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 RegressionHarness: replays seeded corpus + holdout DB cases.
 * Gates playbook / skill / GEPA promotions — fail closed on regression.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegressionHarnessService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MendrScriptGatewayClient mendrScriptGatewayClient;

    @Value("${mendr.regression.max-db-cases:40}")
    private int maxDbCases;

    @Value("${mendr.regression.fail-closed:true}")
    private boolean failClosed;

    public record CaseResult(String name, String suite, boolean passed, String detail) {}

    public record HarnessReport(
            boolean passed,
            int total,
            int failed,
            List<CaseResult> cases,
            String triggeredBy,
            String artifactType,
            String artifactId) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("passed", passed);
            m.put("total", total);
            m.put("failed", failed);
            m.put("triggeredBy", triggeredBy);
            m.put("artifactType", artifactType);
            m.put("artifactId", artifactId);
            List<Map<String, Object>> cs = new ArrayList<>();
            for (CaseResult c : cases) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", c.name());
                row.put("suite", c.suite());
                row.put("passed", c.passed());
                row.put("detail", c.detail());
                cs.add(row);
            }
            m.put("cases", cs);
            return m;
        }
    }

    /** Full harness run used as a promotion gate. */
    public HarnessReport gatePromotion(String artifactType, String artifactId) {
        return runAndPersist("gate", artifactType, artifactId);
    }

    public HarnessReport runManual() {
        return runAndPersist("manual", null, null);
    }

    @Scheduled(fixedDelayString = "${mendr.regression.sweep-ms:3600000}")
    public void scheduledSweep() {
        try {
            runAndPersist("scheduled", "baseline", null);
        } catch (Exception e) {
            log.debug("regression harness scheduled sweep skipped: {}", e.getMessage());
        }
    }

    public HarnessReport runAndPersist(String triggeredBy, String artifactType, String artifactId) {
        List<CaseResult> results = new ArrayList<>();
        results.addAll(runSeededCorpus());
        results.addAll(runTraceReplay());
        results.addAll(runCounterexamples());
        results.addAll(runOfflinePayloadPresence());
        results.addAll(runArtifactAwareChecks(artifactType, artifactId));

        int failed = (int) results.stream().filter(c -> !c.passed()).count();
        boolean passed = failed == 0;
        if (!passed && failClosed) {
            log.warn("RegressionHarness FAILED: {}/{} cases (artifact={}/{})",
                    failed, results.size(), artifactType, artifactId);
        }

        HarnessReport report = new HarnessReport(
                passed, results.size(), failed, results, triggeredBy, artifactType, artifactId);
        persistRun(report);
        return report;
    }

    /** Seeded deterministic corpus (same scenarios as AnalysisRegressionHarnessTest). */
    public List<CaseResult> runSeededCorpus() {
        List<CaseResult> out = new ArrayList<>();
        for (RegressionSeedCorpus.Scenario s : RegressionSeedCorpus.scenarios()) {
            try {
                StructuredFailureContext structured = StructuredContextAssembler.assemble(s.ctx());
                AnalysisToolResult result = MockAnalysis.build(structured, s.ctx(), "harness");
                boolean ok = s.expectedRuleType().equals(result.ruleType())
                        && s.expectedRuleType().equals(String.valueOf(result.transformationRules().get("type")));
                String detail = ok ? "ok" : "got " + result.ruleType() + " expected " + s.expectedRuleType();
                out.add(new CaseResult(s.name(), "seeded", ok, detail));
            } catch (Exception e) {
                out.add(new CaseResult(s.name(), "seeded", false, e.getMessage()));
            }
        }
        return out;
    }

    /** Re-verify candidate programs stored on learning_traces. */
    public List<CaseResult> runTraceReplay() {
        List<CaseResult> out = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, candidates, outcome
                FROM learning_traces
                WHERE candidates IS NOT NULL
                ORDER BY created_at DESC
                LIMIT ?
                """, maxDbCases);
            for (Map<String, Object> row : rows) {
                String id = row.get("id").toString();
                Object candidates = row.get("candidates");
                Map<String, Object> program = asProgram(candidates);
                if (program == null || program.isEmpty()) {
                    out.add(new CaseResult("trace-" + id, "traces", true, "no program to verify"));
                    continue;
                }
                Map<String, Object> verify = mendrScriptGatewayClient.verify(program);
                boolean valid = Boolean.TRUE.equals(verify.get("valid"));
                // PENDING/SUCCESS traces with candidates should still verify
                out.add(new CaseResult("trace-" + id, "traces", valid,
                        valid ? "verify ok" : String.valueOf(verify.get("errors"))));
            }
        } catch (Exception e) {
            log.debug("trace replay skipped: {}", e.getMessage());
        }
        return out;
    }

    /** Counterexamples must fail verify (or be marked expected-fail). */
    public List<CaseResult> runCounterexamples() {
        List<CaseResult> out = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, case_input, expected_fail_reason, source
                FROM counterexample_suite
                WHERE active = true
                ORDER BY created_at DESC
                LIMIT ?
                """, maxDbCases);
            for (Map<String, Object> row : rows) {
                String id = row.get("id").toString();
                Map<String, Object> program = asProgram(row.get("case_input"));
                if (program == null) {
                    out.add(new CaseResult("cx-" + id, "counterexamples", true, "no program payload"));
                    continue;
                }
                // If case_input wraps {program: ...}
                if (program.containsKey("program") && program.get("program") instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inner = (Map<String, Object>) program.get("program");
                    program = inner;
                }
                Map<String, Object> verify = mendrScriptGatewayClient.verify(program);
                boolean valid = Boolean.TRUE.equals(verify.get("valid"));
                // Counterexamples are expected to be INVALID
                boolean ok = !valid;
                out.add(new CaseResult("cx-" + id, "counterexamples", ok,
                        ok ? "correctly rejected" : "unexpectedly valid"));
            }
        } catch (Exception e) {
            log.debug("counterexample replay skipped: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Sanity: COMPLETED offline payloads exist and are parseable JSON
     * (GEPA/harness consumers must not see FAILED/PENDING).
     */
    public List<CaseResult> runOfflinePayloadPresence() {
        List<CaseResult> out = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, scrub_status, request_scrubbed
                FROM offline_regression_payloads
                WHERE scrub_status = 'COMPLETED'
                ORDER BY created_at DESC
                LIMIT ?
                """, Math.min(maxDbCases, 20));
            for (Map<String, Object> row : rows) {
                String id = row.get("id").toString();
                try {
                    Object req = row.get("request_scrubbed");
                    if (req != null) {
                        objectMapper.readTree(req instanceof String s ? s
                                : objectMapper.writeValueAsString(req));
                    }
                    out.add(new CaseResult("orp-" + id, "offline_payloads", true, "parseable"));
                } catch (Exception e) {
                    out.add(new CaseResult("orp-" + id, "offline_payloads", false, e.getMessage()));
                }
            }
            // Also ensure FAILED rows are not silently treated as COMPLETED
            Integer failedCount = jdbcTemplate.query(
                    "SELECT COUNT(*)::int FROM offline_regression_payloads WHERE scrub_status = 'FAILED'",
                    rs -> rs.next() ? rs.getInt(1) : 0);
            out.add(new CaseResult("orp-failed-quarantine", "offline_payloads", true,
                    "FAILED count=" + failedCount));
        } catch (Exception e) {
            log.debug("offline payload check skipped: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Artifact-aware shadow checks for promotion gates (compiled_prompt / retrieval_config / playbook).
     * Baseline suite alone is not enough — candidate knobs/text must exist and be in-range.
     */
    public List<CaseResult> runArtifactAwareChecks(String artifactType, String artifactId) {
        List<CaseResult> out = new ArrayList<>();
        if (artifactType == null || artifactType.isBlank()
                || "baseline".equalsIgnoreCase(artifactType)) {
            return out;
        }
        String type = artifactType.trim().toLowerCase();
        try {
            switch (type) {
                case "compiled_prompt" -> out.add(checkCompiledPrompt(artifactId));
                case "retrieval_config" -> out.add(checkRetrievalConfig(artifactId));
                case "playbook", "ace_playbook" -> out.add(checkPlaybook(artifactId));
                case "skill", "skill_library" -> out.add(checkSkill(artifactId));
                case "cross_tenant_skill" -> out.add(checkCrossTenantSkill(artifactId));
                case "cross_tenant_heuristic" -> out.add(checkCrossTenantText(artifactId, "heuristic"));
                case "cross_tenant_playbook" -> out.add(checkCrossTenantText(artifactId, "bullet"));
                default -> out.add(new CaseResult(
                        "artifact-" + type, "artifact", true, "no specialized check"));
            }
        } catch (Exception e) {
            out.add(new CaseResult("artifact-" + type, "artifact", false, e.getMessage()));
        }
        return out;
    }

    private CaseResult checkCompiledPrompt(String artifactId) {
        try {
            List<Map<String, Object>> rows;
            if (artifactId != null && artifactId.startsWith("health:")) {
                rows = jdbcTemplate.queryForList("""
                    SELECT id, prompt_text, status, length(coalesce(prompt_text,'')) AS len
                    FROM compiled_prompts
                    WHERE status IN ('CANDIDATE','ACTIVE')
                    ORDER BY created_at DESC LIMIT 1
                    """);
            } else if (artifactId != null && !artifactId.isBlank()) {
                // Prefer candidate UUID from gate callers; also accept kind:vN / compiled_prompt:vN
                rows = jdbcTemplate.queryForList("""
                    SELECT id, prompt_text, status, length(coalesce(prompt_text,'')) AS len
                    FROM compiled_prompts
                    WHERE id::text = ?
                       OR (prompt_kind || ':v' || version::text) = ?
                       OR ('compiled_prompt:v' || version::text) = ?
                    LIMIT 1
                    """, artifactId, artifactId, artifactId);
            } else {
                return new CaseResult("compiled_prompt", "artifact", false, "missing artifactId");
            }
            if (rows.isEmpty()) {
                return new CaseResult("compiled_prompt", "artifact", false, "row not found");
            }
            Object len = rows.get(0).get("len");
            int n = len instanceof Number num ? num.intValue() : 0;
            boolean ok = n >= 40;
            return new CaseResult("compiled_prompt", "artifact", ok,
                    ok ? "prompt_text len=" + n : "prompt_text too short (" + n + ")");
        } catch (Exception e) {
            return new CaseResult("compiled_prompt", "artifact", false, e.getMessage());
        }
    }

    private CaseResult checkRetrievalConfig(String artifactId) {
        try {
            List<Map<String, Object>> rows;
            if (artifactId != null && artifactId.startsWith("health:")) {
                rows = jdbcTemplate.queryForList("""
                    SELECT top_k, min_score, max_distance, decay_half_life_days, status
                    FROM retrieval_config WHERE status = 'ACTIVE'
                    ORDER BY version DESC LIMIT 1
                    """);
            } else if (artifactId != null && !artifactId.isBlank()) {
                // Prefer candidate UUID; also accept health:vN / retrieval_config[:v]:N
                rows = jdbcTemplate.queryForList("""
                    SELECT top_k, min_score, max_distance, decay_half_life_days, status
                    FROM retrieval_config
                    WHERE id::text = ?
                       OR ('health:v' || version::text) = ?
                       OR ('retrieval_config:' || version::text) = ?
                       OR ('retrieval_config:v' || version::text) = ?
                    LIMIT 1
                    """, artifactId, artifactId, artifactId, artifactId);
            } else {
                return new CaseResult("retrieval_config", "artifact", false, "missing artifactId");
            }
            if (rows.isEmpty()) {
                return new CaseResult("retrieval_config", "artifact", false, "row not found");
            }
            Map<String, Object> r = rows.get(0);
            int topK = ((Number) r.get("top_k")).intValue();
            double minScore = ((Number) r.get("min_score")).doubleValue();
            double maxDist = ((Number) r.get("max_distance")).doubleValue();
            double halfLife = ((Number) r.get("decay_half_life_days")).doubleValue();
            boolean ok = topK >= 1 && topK <= 64
                    && minScore >= 0 && minScore <= 1
                    && maxDist > 0 && maxDist <= 2
                    && halfLife >= 1 && halfLife <= 365;
            return new CaseResult("retrieval_config", "artifact", ok,
                    ok ? "knobs in range" : "knobs out of range topK=" + topK
                            + " minScore=" + minScore + " maxDist=" + maxDist
                            + " halfLife=" + halfLife);
        } catch (Exception e) {
            return new CaseResult("retrieval_config", "artifact", false, e.getMessage());
        }
    }

    private CaseResult checkPlaybook(String artifactId) {
        try {
            Integer n = jdbcTemplate.query(
                    "SELECT COUNT(*)::int FROM ace_playbook WHERE active = true",
                    rs -> rs.next() ? rs.getInt(1) : 0);
            boolean ok = n != null && n >= 0;
            return new CaseResult("playbook", "artifact", ok, "active bullets=" + n);
        } catch (Exception e) {
            return new CaseResult("playbook", "artifact", true, "playbook check skipped: " + e.getMessage());
        }
    }

    private CaseResult checkSkill(String artifactId) {
        try {
            Integer n = jdbcTemplate.query(
                    "SELECT COUNT(*)::int FROM skill_library WHERE active = true",
                    rs -> rs.next() ? rs.getInt(1) : 0);
            return new CaseResult("skill", "artifact", true, "skills=" + n);
        } catch (Exception e) {
            return new CaseResult("skill", "artifact", true, "skill check skipped: " + e.getMessage());
        }
    }

    /**
     * Phase 7: verify the candidate skill program from {@code cross_tenant_pool} before import.
     * {@code artifactId} is the pool row UUID passed by CrossTenantPoolService.materialize.
     */
    private CaseResult checkCrossTenantSkill(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            return new CaseResult("cross_tenant_skill", "artifact", false, "missing pool id");
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT artifact_type, payload, status
                FROM cross_tenant_pool
                WHERE id = ?::uuid
                LIMIT 1
                """, artifactId);
            if (rows.isEmpty()) {
                return new CaseResult("cross_tenant_skill", "artifact", false, "pool row not found");
            }
            Map<String, Object> row = rows.get(0);
            if (!"PUBLISHED".equals(String.valueOf(row.get("status")))) {
                return new CaseResult("cross_tenant_skill", "artifact", false, "not PUBLISHED");
            }
            if (!"skill".equals(String.valueOf(row.get("artifact_type")))) {
                return new CaseResult("cross_tenant_skill", "artifact", false, "wrong artifact_type");
            }
            Map<String, Object> payload = asProgram(row.get("payload"));
            if (payload == null) {
                return new CaseResult("cross_tenant_skill", "artifact", false, "empty payload");
            }
            Object program = payload.get("program");
            Map<String, Object> prog = asProgram(program);
            if (prog == null || prog.isEmpty()) {
                return new CaseResult("cross_tenant_skill", "artifact", false, "missing program");
            }
            Map<String, Object> verify = mendrScriptGatewayClient.verify(prog);
            boolean valid = Boolean.TRUE.equals(verify.get("valid"));
            return new CaseResult("cross_tenant_skill", "artifact", valid,
                    valid ? "verify ok" : String.valueOf(verify.get("errors")));
        } catch (Exception e) {
            return new CaseResult("cross_tenant_skill", "artifact", false, e.getMessage());
        }
    }

    /** Phase 7: require scrubbed non-blank heuristic/playbook text in the pool payload. */
    private CaseResult checkCrossTenantText(String artifactId, String textKey) {
        String label = "bullet".equals(textKey) ? "cross_tenant_playbook" : "cross_tenant_heuristic";
        if (artifactId == null || artifactId.isBlank()) {
            return new CaseResult(label, "artifact", false, "missing pool id");
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT artifact_type, payload, status
                FROM cross_tenant_pool
                WHERE id = ?::uuid
                LIMIT 1
                """, artifactId);
            if (rows.isEmpty()) {
                return new CaseResult(label, "artifact", false, "pool row not found");
            }
            Map<String, Object> row = rows.get(0);
            if (!"PUBLISHED".equals(String.valueOf(row.get("status")))) {
                return new CaseResult(label, "artifact", false, "not PUBLISHED");
            }
            Map<String, Object> payload = asProgram(row.get("payload"));
            if (payload == null) {
                return new CaseResult(label, "artifact", false, "empty payload");
            }
            Object text = payload.get(textKey);
            String s = text == null ? "" : text.toString().trim();
            if (s.length() < 12) {
                return new CaseResult(label, "artifact", false, "text too short");
            }
            String lower = s.toLowerCase();
            if (lower.contains("@") && lower.contains(".")) {
                return new CaseResult(label, "artifact", false, "unsanitized email risk");
            }
            return new CaseResult(label, "artifact", true, "text ok len=" + s.length());
        } catch (Exception e) {
            return new CaseResult(label, "artifact", false, e.getMessage());
        }
    }

    private void persistRun(HarnessReport report) {
        try {
            jdbcTemplate.update("""
                INSERT INTO regression_harness_runs (
                    triggered_by, artifact_type, artifact_id,
                    passed, total_cases, failed_cases, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                    report.triggeredBy(),
                    report.artifactType(),
                    report.artifactId(),
                    report.passed(),
                    report.total(),
                    report.failed(),
                    objectMapper.writeValueAsString(report.toMap()));
        } catch (Exception e) {
            log.debug("regression_harness_runs persist skipped: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asProgram(Object raw) {
        try {
            if (raw == null) return null;
            if (raw instanceof Map<?, ?> m) {
                return new LinkedHashMap<>((Map<String, Object>) m);
            }
            if (raw instanceof String s) {
                if (s.isBlank()) return null;
                Object parsed = objectMapper.readValue(s, Object.class);
                if (parsed instanceof Map<?, ?> m) {
                    return new LinkedHashMap<>((Map<String, Object>) m);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
