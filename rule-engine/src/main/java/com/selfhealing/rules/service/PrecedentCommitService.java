package com.selfhealing.rules.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.rules.service.embed.SignatureEmbedder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Commits a CANDIDATE row to {@code error_precedents} after an APPROVED deploy
 * so Phase 6 hybrid GraphRAG can recall verified programs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrecedentCommitService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public void commitCandidate(String analysisIdStr, String failureIdStr, Map<String, Object> rules) {
        if (analysisIdStr == null || analysisIdStr.isBlank() || rules == null || rules.isEmpty()) {
            return;
        }
        try {
            UUID analysisId = UUID.fromString(analysisIdStr);
            UUID failureId = failureIdStr != null && !failureIdStr.isBlank()
                    ? UUID.fromString(failureIdStr) : null;

            Map<String, Object> row = jdbcTemplate.query(
                    """
                    SELECT ar.analysis_metadata, ar.transformation_rules, ar.tenant_id,
                           af.service_a, af.service_b, af.endpoint
                    FROM analysis_results ar
                    LEFT JOIN api_failures af ON af.id = ar.failure_id
                    WHERE ar.id = ?
                    LIMIT 1
                    """,
                    rs -> {
                        if (!rs.next()) return null;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("analysis_metadata", rs.getString("analysis_metadata"));
                        m.put("transformation_rules", rs.getString("transformation_rules"));
                        m.put("tenant_id", rs.getObject("tenant_id"));
                        m.put("service_a", rs.getString("service_a"));
                        m.put("service_b", rs.getString("service_b"));
                        m.put("endpoint", rs.getString("endpoint"));
                        return m;
                    },
                    analysisId);

            if (row == null) {
                log.debug("No analysis_results row for {} — skip precedent commit", analysisId);
                return;
            }

            Map<String, Object> sig = extractSignature(row.get("analysis_metadata"));
            if (sig.isEmpty()) {
                // Minimal signature from route + rule type so embedding still works
                sig = new LinkedHashMap<>();
                sig.put("change_type", rules.get("type"));
                Map<String, Object> coords = new LinkedHashMap<>();
                coords.put("service", row.get("service_b"));
                coords.put("endpoint", row.get("endpoint"));
                coords.put("direction", "REQUEST");
                sig.put("contract_coords", coords);
                sig.put("category", "UNKNOWN");
            }

            String signatureText = SignatureEmbedder.canonicalText(sig);
            String vectorLit = SignatureEmbedder.toVectorLiteral(SignatureEmbedder.embedSignature(sig));
            String programJson = objectMapper.writeValueAsString(rules);

            Double specTrust = asDouble(sig.get("spec_trust"));
            if (specTrust == null) specTrust = 0.5;

            Object tenantId = row.get("tenant_id");

            jdbcTemplate.update("""
                INSERT INTO error_precedents (
                    tenant_id, analysis_id, failure_id,
                    category, change_type, json_path, template_id, contract_ref,
                    embedding, signature_text, program,
                    outcome, quality, spec_trust,
                    source_service, target_service, endpoint,
                    approved_at
                ) VALUES (
                    ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?::vector, ?, ?::jsonb,
                    'PENDING', 'CANDIDATE', ?,
                    ?, ?, ?,
                    NOW()
                )
                """,
                    tenantId, analysisId, failureId,
                    str(sig.get("category")),
                    str(sig.get("change_type")),
                    str(sig.get("json_path")),
                    str(sig.get("template_id")),
                    str(sig.get("contract_ref")),
                    vectorLit, signatureText, programJson,
                    specTrust,
                    row.get("service_a"), row.get("service_b"), row.get("endpoint"));

            log.info("Committed CANDIDATE error_precedent for analysis {}", analysisId);
        } catch (Exception e) {
            // Never fail deploy because memory write failed
            log.warn("error_precedents commit skipped for analysis {}: {}", analysisIdStr, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSignature(Object metaRaw) throws Exception {
        if (metaRaw == null) return Map.of();
        Map<String, Object> meta;
        if (metaRaw instanceof String s) {
            if (s.isBlank()) return Map.of();
            meta = objectMapper.readValue(s, Map.class);
        } else if (metaRaw instanceof Map<?, ?> m) {
            meta = (Map<String, Object>) m;
        } else {
            return Map.of();
        }
        Object es = meta.get("errorSignature");
        if (es instanceof Map<?, ?> em) {
            return (Map<String, Object>) em;
        }
        return Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
