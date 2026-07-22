package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.bandit.BanditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wilson-score quality lifecycle for Phase 8.4 precedents.
 * Promote / demote using Wilson interval lower bound — not a flat quiet-window timer.
 * Dual-outcome: SUCCESS / FAILURE feeds conformal labels and async bandit credit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrecedentsQualitySweeper {

    private final JdbcTemplate jdbcTemplate;
    private final BanditService banditService;
    private final ObjectMapper objectMapper;

    /** Minimal age before Wilson may promote (race guard only — not a quality gate). */
    @Value("${mendr.quality.min-age-minutes:1}")
    private int minAgeMinutes;

    @Value("${mendr.quality.wilson-min-n:3}")
    private int wilsonMinN;

    @Value("${mendr.quality.wilson-z:1.96}")
    private double wilsonZ;

    @Value("${mendr.quality.wilson-promote-threshold:0.85}")
    private double promoteThreshold;

    @Value("${mendr.quality.wilson-demote-threshold:0.40}")
    private double demoteThreshold;

    @Scheduled(fixedDelayString = "${mendr.precedents.quality-sweep-ms:300000}")
    public void sweep() {
        try {
            // No flat 60-minute quiet window — Wilson decides with recurrence evidence.
            // min-age is only a race guard so we don't promote in the same second as approve.
            List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT id, analysis_id, failure_id, change_type, json_path,
                       source_service, target_service, endpoint, approved_at,
                       bandit_category,
                       EXTRACT(EPOCH FROM (NOW() - approved_at)) / 60.0 AS age_minutes
                FROM error_precedents
                WHERE quality = 'CANDIDATE'
                  AND approved_at IS NOT NULL
                  AND approved_at <= NOW() - make_interval(mins => ?)
                ORDER BY approved_at ASC
                LIMIT 50
                """, Math.max(0, minAgeMinutes));

            for (Map<String, Object> row : candidates) {
                promoteOrReject(row);
            }
        } catch (Exception e) {
            log.debug("precedents quality sweep skipped: {}", e.getMessage());
        }
    }

    private void promoteOrReject(Map<String, Object> row) {
        Object id = row.get("id");
        String source = str(row.get("source_service"));
        String target = str(row.get("target_service"));
        String endpoint = str(row.get("endpoint"));
        String changeType = str(row.get("change_type"));
        String jsonPath = str(row.get("json_path"));

        if (isBlank(target) || isBlank(endpoint)) {
            log.debug("Precedent {} skipped — missing target/endpoint for recurrence check", id);
            return;
        }

        try {
            Integer recurred = jdbcTemplate.query(
                    """
                    SELECT COUNT(*)::int
                    FROM api_failures af
                    WHERE af.detected_at >= ?
                      AND (? IS NULL OR af.service_a = ?)
                      AND af.service_b = ?
                      AND af.endpoint = ?
                      AND (
                        ? IS NULL OR ? = ''
                        OR COALESCE(af.error_message, '') ILIKE '%' || ? || '%'
                        OR COALESCE(af.error_type, '') ILIKE '%' || ? || '%'
                      )
                      AND (
                        ? IS NULL OR ? = ''
                        OR UPPER(COALESCE(af.error_type, '')) = UPPER(?)
                        OR UPPER(COALESCE(af.error_type, '')) LIKE '%' || UPPER(?) || '%'
                        OR COALESCE(af.error_message, '') ILIKE '%' || ? || '%'
                      )
                    """,
                    rs -> rs.next() ? rs.getInt(1) : 0,
                    row.get("approved_at"),
                    source, source,
                    target,
                    endpoint,
                    jsonPath, jsonPath, jsonPath, jsonPath,
                    changeType, changeType, changeType, changeType, changeType);

            int failures = recurred == null ? 0 : Math.min(recurred, 20);
            // Evidence accumulates with age without a flat 60m gate:
            // ~1 success observation per 5 clean minutes, capped; recurrence → failures.
            double ageMinutes = row.get("age_minutes") instanceof Number n
                    ? n.doubleValue() : minAgeMinutes;
            int successes = failures == 0
                    ? Math.max(1, Math.min(wilsonMinN * 3, (int) Math.floor(ageMinutes / 5.0) + 1))
                    : 0;
            int n = successes + failures;
            if (n < wilsonMinN && failures == 0) {
                // Update Wilson stats but keep CANDIDATE until enough clean evidence
                WilsonInterval early = wilson(successes, Math.max(n, 1), wilsonZ);
                try {
                    jdbcTemplate.update("""
                        UPDATE error_precedents
                        SET wilson_lower = ?, wilson_upper = ?, wilson_n = ?
                        WHERE id = ?::uuid
                        """, early.lower(), early.upper(), n, id.toString());
                } catch (Exception ignored) {
                    // column may be absent on old volumes
                }
                return;
            }

            WilsonInterval wilson = wilson(successes, n, wilsonZ);
            Map<String, Object> dims = new LinkedHashMap<>();
            dims.put("correctness", failures == 0 ? 1.0 : Math.max(0.0, 1.0 - (double) failures / Math.max(n, 1)));
            dims.put("metamorphicCompleteness", readMetaDim(row.get("analysis_id"), "metamorphicPassRate", 0.5));
            dims.put("opCountEfficiency", readOpCountEfficiency(row.get("analysis_id")));
            dims.put("categoryMatch", row.get("bandit_category") != null || row.get("change_type") != null ? 0.8 : 0.5);
            String dimsJson = objectMapper.writeValueAsString(dims);

            UUID analysisId = row.get("analysis_id") == null ? null
                    : UUID.fromString(row.get("analysis_id").toString());

            if (failures > 0 || wilson.lower() < demoteThreshold) {
                jdbcTemplate.update("""
                    UPDATE error_precedents
                    SET quality = 'REJECTED', outcome = 'FAILURE', recurred = true,
                        verified_at = NOW(), wilson_lower = ?, wilson_upper = ?, wilson_n = ?,
                        quality_dims = ?::jsonb, demote_reason = ?
                    WHERE id = ?::uuid
                    """,
                        wilson.lower(), wilson.upper(), n, dimsJson,
                        failures > 0 ? "recurrence_count=" + failures : "wilson_lower=" + wilson.lower(),
                        id.toString());
                log.info("Precedent {} REJECTED (wilsonL={}, n={}, recurred={})",
                        id, wilson.lower(), n, failures);
                if (analysisId != null) banditService.resolvePending(analysisId, false);
            } else if (wilson.lower() >= promoteThreshold) {
                jdbcTemplate.update("""
                    UPDATE error_precedents
                    SET quality = 'TRUSTED', outcome = 'SUCCESS', recurred = false,
                        verified_at = NOW(), wilson_lower = ?, wilson_upper = ?, wilson_n = ?,
                        quality_dims = ?::jsonb, demote_reason = NULL
                    WHERE id = ?::uuid
                    """,
                        wilson.lower(), wilson.upper(), n, dimsJson, id.toString());
                log.info("Precedent {} promoted to TRUSTED (wilsonL={}, n={})", id, wilson.lower(), n);
                if (analysisId != null) banditService.resolvePending(analysisId, true);
            } else {
                // Update Wilson stats but keep CANDIDATE
                jdbcTemplate.update("""
                    UPDATE error_precedents
                    SET wilson_lower = ?, wilson_upper = ?, wilson_n = ?,
                        quality_dims = ?::jsonb
                    WHERE id = ?::uuid
                    """, wilson.lower(), wilson.upper(), n, dimsJson, id.toString());
            }
        } catch (Exception e) {
            log.debug("promote/reject failed for {}: {}", id, e.getMessage());
        }
    }

    public record WilsonInterval(double lower, double upper) {}

    /** Public for unit tests and quality lifecycle callers. */
    public static WilsonInterval wilson(int successes, int n, double z) {
        if (n <= 0) return new WilsonInterval(0, 1);
        double phat = (double) successes / n;
        double z2 = z * z;
        double denom = 1.0 + z2 / n;
        double centre = phat + z2 / (2.0 * n);
        double margin = z * Math.sqrt((phat * (1.0 - phat) + z2 / (4.0 * n)) / n);
        double lower = (centre - margin) / denom;
        double upper = (centre + margin) / denom;
        return new WilsonInterval(Math.max(0, lower), Math.min(1, upper));
    }

    private double readMetaDim(Object analysisId, String scoreKey, double fallback) {
        if (analysisId == null) return fallback;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT analysis_metadata FROM analysis_results WHERE id = ?::uuid LIMIT 1
                """, analysisId.toString());
            if (rows.isEmpty()) return fallback;
            Object am = rows.get(0).get("analysis_metadata");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = am instanceof Map
                    ? (Map<String, Object>) am
                    : objectMapper.readValue(am.toString(), Map.class);
            Object ss = meta.get("safetyScore");
            if (ss instanceof Map<?, ?> scoreMap && scoreMap.get(scoreKey) instanceof Number n) {
                return n.doubleValue();
            }
            Object mm = meta.get("metamorphic");
            if ("metamorphicPassRate".equals(scoreKey) && mm instanceof Map<?, ?> m
                    && m.get("passRate") instanceof Number n) {
                return n.doubleValue();
            }
        } catch (Exception ignored) {
            // fallback
        }
        return fallback;
    }

    private double readOpCountEfficiency(Object analysisId) {
        if (analysisId == null) return 0.5;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT transformation_rules FROM analysis_results WHERE id = ?::uuid LIMIT 1
                """, analysisId.toString());
            if (rows.isEmpty()) return 0.5;
            Object rules = rows.get(0).get("transformation_rules");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = rules instanceof Map
                    ? (Map<String, Object>) rules
                    : objectMapper.readValue(rules.toString(), Map.class);
            Object ops = map.get("ops");
            int count = ops instanceof List<?> list ? list.size() : 3;
            // Prefer ≤3 ops; degrade after that
            return count <= 1 ? 1.0 : count <= 3 ? 0.85 : Math.max(0.3, 1.0 - 0.1 * count);
        } catch (Exception e) {
            return 0.5;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
