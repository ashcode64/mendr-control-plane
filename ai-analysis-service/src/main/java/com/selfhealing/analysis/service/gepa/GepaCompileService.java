package com.selfhealing.analysis.service.gepa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.dspy.DspyPromptCompileGate;
import com.selfhealing.analysis.service.regression.RegressionHarnessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6 GEPA compile job: builds propose_addendum from scrubbed offline corpus
 * + learning_traces critic text. Shadow-evals via {@link RegressionHarnessService}
 * before promoting to {@code compiled_prompts}. Never reads raw api_failures.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GepaCompileService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RegressionHarnessService regressionHarness;
    private final GepaCompileGate gepaGate;
    private final DspyPromptCompileGate dspyGate;

    @Value("${mendr.gepa.compile-limit:60}")
    private int compileLimit;

    @Value("${mendr.conversation.gepa-compile-url:}")
    private String gepaCompileUrl;

    @Value("${gateway.internal.api-key:}")
    private String internalApiKey;

    @Scheduled(fixedDelayString = "${mendr.gepa.compile-ms:7200000}")
    public void scheduledCompile() {
        try {
            compileAndMaybePromote(null);
        } catch (Exception e) {
            log.debug("GEPA compile skipped: {}", e.getMessage());
        }
    }

    public Map<String, Object> compileAndMaybePromote(UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gate", gepaGate.status());
        out.put("dspyGate", dspyGate.status());
        if (!gepaGate.canCompile()) {
            out.put("compiled", false);
            out.put("reason", gepaGate.status());
            return out;
        }

        List<Map<String, Object>> dataset = loadScrubbedDataset(tenantId);
        out.put("datasetSize", dataset.size());
        if (dataset.size() < 3) {
            out.put("compiled", false);
            out.put("reason", "insufficient_scrubbed_examples");
            return out;
        }

        CompileResult compiled = preferRemoteGepa(dataset);
        if (compiled == null) {
            compiled = miproFallback(dataset);
        }
        out.put("compiler", compiled.compiler());
        out.put("promptChars", compiled.promptText().length());

        int nextVersion = nextVersion(tenantId, "propose_addendum");
        UUID id = insertCandidate(tenantId, nextVersion, compiled, dataset.size());
        if (id == null) {
            out.put("compiled", false);
            out.put("reason", "insert_failed");
            return out;
        }
        out.put("candidateId", id.toString());
        out.put("version", nextVersion);

        // Shadow-eval → promote (pass candidate UUID so artifact-aware harness can load the row)
        boolean promoted = promoteIfHarnessPasses(id, id.toString());
        out.put("promoted", promoted);
        out.put("compiled", true);
        return out;
    }

    public Map<String, Object> fetchActive(UUID tenantId, String promptKind) {
        String kind = promptKind == null || promptKind.isBlank() ? "propose_addendum" : promptKind;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, version, prompt_kind, prompt_text, compiler, dataset_size, metrics
                FROM compiled_prompts
                WHERE status = 'ACTIVE'
                  AND prompt_kind = ?
                  AND (tenant_id IS NULL OR tenant_id IS NOT DISTINCT FROM ?::uuid)
                ORDER BY
                  CASE WHEN tenant_id IS NOT DISTINCT FROM ?::uuid THEN 0 ELSE 1 END,
                  version DESC
                LIMIT 1
                """,
                    kind,
                    tenantId == null ? null : tenantId.toString(),
                    tenantId == null ? null : tenantId.toString());
            if (rows.isEmpty()) return Map.of("found", false);
            Map<String, Object> row = rows.get(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", true);
            out.put("id", row.get("id"));
            out.put("version", row.get("version"));
            out.put("promptKind", row.get("prompt_kind"));
            out.put("promptText", row.get("prompt_text"));
            out.put("compiler", row.get("compiler"));
            out.put("datasetSize", row.get("dataset_size"));
            return out;
        } catch (Exception e) {
            log.debug("compiled_prompts fetch skipped: {}", e.getMessage());
            return Map.of("found", false, "error", e.getMessage());
        }
    }

    private CompileResult preferRemoteGepa(List<Map<String, Object>> dataset) {
        if (!gepaGate.preferDspyGepa()) return null;
        if (gepaCompileUrl == null || gepaCompileUrl.isBlank()) {
            log.debug("GEPA remote URL unset — using MIPRO fallback");
            return null;
        }
        try {
            WebClient client = WebClient.builder().build();
            Map<String, Object> body = Map.of("examples", dataset);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = client.post()
                    .uri(gepaCompileUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (internalApiKey != null && !internalApiKey.isBlank()) {
                            h.set("X-Internal-Api-Key", internalApiKey);
                        }
                    })
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(120));
            if (resp == null || resp.get("promptText") == null) return null;
            String compiler = "gepa".equals(String.valueOf(resp.get("compiler"))) ? "gepa" : "mipro_fallback";
            return new CompileResult(compiler, String.valueOf(resp.get("promptText")),
                    resp.get("metrics") instanceof Map<?, ?> m
                            ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of());
        } catch (Exception e) {
            log.warn("Remote GEPA compile failed, falling back to MIPRO: {}", e.getMessage());
            return null;
        }
    }

    /**
     * MIPROv2-style fallback: distill critic_text + scrubbed signature hints into
     * a bounded propose addendum (no LLM required).
     */
    static CompileResult miproFallback(List<Map<String, Object>> dataset) {
        Set<String> tips = new LinkedHashSet<>();
        int withCritic = 0;
        for (Map<String, Object> ex : dataset) {
            String critic = str(ex.get("critic_text"));
            if (critic != null && critic.length() > 8) {
                withCritic++;
                tips.add(tipFromCritic(critic, str(ex.get("change_type")), str(ex.get("json_path"))));
            }
            String change = str(ex.get("change_type"));
            if (change != null) {
                tips.add("Honor verified change_type=" + change
                        + " — do not invent unrelated opcodes.");
            }
        }
        tips.add("Use only scrubbed offline evidence; never invent fields outside ErrorSignature.");
        tips.add("Prefer causally_verified_root_causes over untested guesses.");

        StringBuilder sb = new StringBuilder();
        sb.append("GEPA/MIPRO compiled propose addendum (fallback compiler).\n");
        int n = 0;
        for (String tip : tips) {
            if (tip == null || tip.isBlank()) continue;
            sb.append("- ").append(tip.trim()).append('\n');
            if (++n >= 16) break;
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("examples", dataset.size());
        metrics.put("withCritic", withCritic);
        metrics.put("tips", n);
        return new CompileResult("mipro_fallback", sb.toString().trim(), metrics);
    }

    static String tipFromCritic(String critic, String changeType, String jsonPath) {
        String c = critic.trim();
        if (c.length() > 220) c = c.substring(0, 220) + "…";
        String lower = c.toLowerCase(Locale.ROOT);
        if (lower.contains("protected") || lower.contains("authorization")) {
            return "Never touch protected fields — critic: " + c;
        }
        if (lower.contains("rename") && lower.contains("coerce")) {
            return "Do not confuse FIELD_RENAME with TYPE_COERCE"
                    + (jsonPath != null ? " near " + jsonPath : "")
                    + " — critic: " + c;
        }
        return "Critic feedback"
                + (changeType != null ? " for " + changeType : "")
                + (jsonPath != null ? " at " + jsonPath : "")
                + ": " + c;
    }

    private List<Map<String, Object>> loadScrubbedDataset(UUID tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            // COMPLETED offline payloads only — never raw api_failures
            List<Map<String, Object>> payloads = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, request_scrubbed, response_scrubbed, scrub_status
                FROM offline_regression_payloads
                WHERE scrub_status = 'COMPLETED'
                  AND (?::uuid IS NULL OR tenant_id IS NOT DISTINCT FROM ?::uuid)
                ORDER BY updated_at DESC
                LIMIT ?
                """,
                    tenantId == null ? null : tenantId.toString(),
                    tenantId == null ? null : tenantId.toString(),
                    compileLimit);
            for (Map<String, Object> row : payloads) {
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put("source", "offline_regression_payloads");
                ex.put("id", row.get("id"));
                ex.put("request", parseJson(row.get("request_scrubbed")));
                ex.put("response", parseJson(row.get("response_scrubbed")));
                out.add(ex);
            }
        } catch (Exception e) {
            log.debug("offline payload load skipped: {}", e.getMessage());
        }
        try {
            // Only traces whose failure has a COMPLETED scrub — never unscrubbed critic text.
            List<Map<String, Object>> traces = jdbcTemplate.queryForList("""
                SELECT lt.id, lt.tenant_id, lt.error_signature, lt.critic_text,
                       lt.bandit_category, lt.outcome
                FROM learning_traces lt
                WHERE lt.critic_text IS NOT NULL
                  AND length(trim(lt.critic_text)) > 8
                  AND lt.failure_id IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM offline_regression_payloads orp
                    WHERE orp.failure_id = lt.failure_id
                      AND orp.scrub_status = 'COMPLETED'
                  )
                  AND (?::uuid IS NULL OR lt.tenant_id IS NOT DISTINCT FROM ?::uuid)
                ORDER BY lt.created_at DESC
                LIMIT ?
                """,
                    tenantId == null ? null : tenantId.toString(),
                    tenantId == null ? null : tenantId.toString(),
                    compileLimit);
            for (Map<String, Object> row : traces) {
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put("source", "learning_traces");
                ex.put("id", row.get("id"));
                String critic = row.get("critic_text") == null ? null
                        : com.selfhealing.analysis.service.PiiScrubJob.scrubString(
                                String.valueOf(row.get("critic_text")));
                ex.put("critic_text", critic);
                Map<String, Object> sig = asMap(row.get("error_signature"));
                if (sig.get("change_type") != null) ex.put("change_type", sig.get("change_type"));
                if (sig.get("json_path") != null) ex.put("json_path", sig.get("json_path"));
                if (row.get("bandit_category") != null) {
                    ex.put("bandit_category", row.get("bandit_category"));
                }
                out.add(ex);
            }
        } catch (Exception e) {
            log.debug("learning_traces load skipped: {}", e.getMessage());
        }
        return out;
    }

    private boolean promoteIfHarnessPasses(UUID candidateId, String artifactId) {
        RegressionHarnessService.HarnessReport report =
                regressionHarness.gatePromotion("compiled_prompt", artifactId);
        if (!report.passed()) {
            log.warn("GEPA promote blocked by RegressionHarness id={}", candidateId);
            return false;
        }
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT tenant_id, prompt_kind, version FROM compiled_prompts WHERE id = ?::uuid
                """, candidateId.toString());
            UUID tenant = row.get("tenant_id") == null ? null
                    : UUID.fromString(row.get("tenant_id").toString());
            String kind = String.valueOf(row.get("prompt_kind"));

            jdbcTemplate.update("""
                UPDATE compiled_prompts
                SET status = 'REVERTED', reverted_at = NOW()
                WHERE status = 'ACTIVE'
                  AND prompt_kind = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                """, kind, tenant == null ? null : tenant.toString());

            jdbcTemplate.update("""
                UPDATE compiled_prompts
                SET status = 'ACTIVE', activated_at = NOW(), harness_passed_at = NOW()
                WHERE id = ?::uuid
                """, candidateId.toString());
            log.info("GEPA promoted compiled_prompts {} v{}", kind, row.get("version"));
            return true;
        } catch (Exception e) {
            log.debug("GEPA promote failed: {}", e.getMessage());
            return false;
        }
    }

    private UUID insertCandidate(UUID tenantId, int version, CompileResult compiled, int datasetSize) {
        try {
            String metricsJson = objectMapper.writeValueAsString(compiled.metrics());
            return jdbcTemplate.query("""
                INSERT INTO compiled_prompts (
                    tenant_id, version, prompt_kind, prompt_text, compiler,
                    status, parent_version, dataset_size, metrics, notes
                ) VALUES (?::uuid, ?, 'propose_addendum', ?, ?, 'CANDIDATE', ?, ?, ?::jsonb, ?)
                ON CONFLICT DO NOTHING
                RETURNING id
                """,
                    rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
                    tenantId == null ? null : tenantId.toString(),
                    version,
                    compiled.promptText(),
                    compiled.compiler(),
                    version > 1 ? version - 1 : null,
                    datasetSize,
                    metricsJson,
                    "Phase 6 offline compile");
        } catch (Exception e) {
            log.debug("compiled_prompts insert failed: {}", e.getMessage());
            return null;
        }
    }

    private int nextVersion(UUID tenantId, String kind) {
        try {
            Integer max = jdbcTemplate.query("""
                SELECT COALESCE(MAX(version), 0) FROM compiled_prompts
                WHERE prompt_kind = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                """,
                    rs -> rs.next() ? rs.getInt(1) : 0,
                    kind, tenantId == null ? null : tenantId.toString());
            return (max == null ? 0 : max) + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private Object parseJson(Object raw) {
        try {
            if (raw == null) return null;
            if (raw instanceof Map || raw instanceof List) return raw;
            return objectMapper.readValue(raw.toString(), Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        Object parsed = parseJson(raw);
        if (parsed instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    record CompileResult(String compiler, String promptText, Map<String, Object> metrics) {}
}
