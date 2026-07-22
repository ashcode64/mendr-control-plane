package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Best-effort writer for {@code learning_traces} (Phase 0 substrate).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningTraceWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void write(
            UUID tenantId,
            UUID failureId,
            UUID analysisId,
            Map<String, Object> errorSignature,
            Map<String, Object> sketch,
            Object driftedFields,
            Object causalMinimal,
            String oraclePath,
            Object verifiedCandidates,
            Object candidates,
            String banditCategory,
            String criticText,
            String outcome) {
        try {
            jdbcTemplate.update("""
                INSERT INTO learning_traces (
                    tenant_id, failure_id, analysis_id,
                    error_signature, sketch,
                    drifted_fields, causal_minimal_fields, oracle_path, verified_candidates,
                    candidates, bandit_category, critic_text, outcome
                ) VALUES (
                    ?, ?, ?,
                    ?::jsonb, ?::jsonb,
                    ?::jsonb, ?::jsonb, ?, ?::jsonb,
                    ?::jsonb, ?, ?, ?
                )
                """,
                    tenantId,
                    failureId,
                    analysisId,
                    json(errorSignature),
                    json(sketch),
                    json(driftedFields),
                    json(causalMinimal),
                    oraclePath,
                    json(verifiedCandidates),
                    json(candidates),
                    banditCategory,
                    criticText,
                    outcome != null ? outcome : "PENDING");
        } catch (Exception e) {
            log.debug("learning_traces write skipped: {}", e.getMessage());
        }
    }

    /** Backfill analysis_id after AnalysisResult is persisted (diagnose runs before save). */
    public void linkAnalysisId(UUID failureId, UUID analysisId) {
        if (failureId == null || analysisId == null) return;
        try {
            jdbcTemplate.update("""
                UPDATE learning_traces
                SET analysis_id = ?
                WHERE failure_id = ?
                  AND analysis_id IS NULL
                  AND created_at > NOW() - INTERVAL '1 hour'
                """, analysisId, failureId);
        } catch (Exception e) {
            log.debug("learning_traces analysis_id link skipped: {}", e.getMessage());
        }
    }

    private String json(Object o) throws Exception {
        if (o == null) return null;
        if (o instanceof String s) return s;
        return objectMapper.writeValueAsString(o);
    }
}
