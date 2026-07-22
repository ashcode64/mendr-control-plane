package com.selfhealing.analysis.service.heuristics;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2 Reflector/Curator: distill critic text + demote_reason into topology-scoped
 * repair heuristics using ExpeL ADD / UPVOTE / DOWNVOTE / EDIT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeuristicReflectorCuratorJob {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RepairHeuristicsService heuristicsService;

    @Value("${mendr.heuristics.reflect-limit:40}")
    private int reflectLimit;

    @Scheduled(fixedDelayString = "${mendr.heuristics.reflect-ms:600000}")
    public void run() {
        try {
            reflectFromTraces();
            reflectFromRejectedPrecedents();
            curateFromTrustedSuccess();
        } catch (Exception e) {
            log.debug("HeuristicReflectorCurator skipped: {}", e.getMessage());
        }
    }

    /** Reflector: learning_traces.critic_text → FAILURE/WARN heuristics (ADD or UPVOTE). */
    private void reflectFromTraces() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, tenant_id, error_signature, critic_text, outcome, bandit_category
            FROM learning_traces
            WHERE critic_text IS NOT NULL
              AND length(trim(critic_text)) > 8
            ORDER BY created_at DESC
            LIMIT ?
            """, reflectLimit);

        for (Map<String, Object> row : rows) {
            Map<String, Object> sig = asMap(row.get("error_signature"));
            String scope = TopologyScope.fromSignature(sig);
            if (scope == null) continue;

            String critic = str(row.get("critic_text"));
            String heuristic = synthesizeFromCritic(critic, sig);
            if (heuristic == null) continue;

            UUID tenant = asUuid(row.get("tenant_id"));
            UUID traceId = asUuid(row.get("id"));
            String category = str(sig.get("category"));
            String changeType = str(sig.get("change_type"));
            if (changeType == null) changeType = str(row.get("bandit_category"));

            // Critic text implies a problem → FAILURE warn-off
            heuristicsService.apply(
                    RepairHeuristicsService.Op.ADD,
                    tenant, scope, heuristic, "FAILURE",
                    category, changeType, traceId, null, null);
        }
    }

    /** Reflector: REJECTED precedents with demote_reason → FAILURE heuristics. */
    private void reflectFromRejectedPrecedents() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, tenant_id, category, change_type, json_path,
                   source_service, target_service, endpoint, demote_reason, quality, outcome
            FROM error_precedents
            WHERE (quality = 'REJECTED' OR outcome = 'FAILURE')
              AND demote_reason IS NOT NULL
            ORDER BY verified_at DESC NULLS LAST
            LIMIT ?
            """, reflectLimit);

        for (Map<String, Object> row : rows) {
            String scope = TopologyScope.of(
                    str(row.get("source_service")),
                    str(row.get("target_service")),
                    str(row.get("endpoint")));
            if (scope == null) continue;

            String reason = str(row.get("demote_reason"));
            String changeType = str(row.get("change_type"));
            String jsonPath = str(row.get("json_path"));
            String heuristic = "Avoid " + (changeType != null ? changeType : "this strategy")
                    + (jsonPath != null ? " at " + jsonPath : "")
                    + " on this route — demoted (" + reason + ").";

            UUID tenant = asUuid(row.get("tenant_id"));
            heuristicsService.apply(
                    RepairHeuristicsService.Op.ADD,
                    tenant,
                    scope,
                    heuristic,
                    "FAILURE",
                    str(row.get("category")),
                    changeType,
                    null,
                    asUuid(row.get("id")),
                    null);

            // ExpeL DOWNVOTE: demote the matching SUCCESS tip for this topology if present
            String successTip = "Prefer " + (changeType != null ? changeType : "structural mapping")
                    + (jsonPath != null ? " at " + jsonPath : "")
                    + " on this route — TRUSTED SUCCESS.";
            heuristicsService.apply(
                    RepairHeuristicsService.Op.DOWNVOTE,
                    tenant, scope, successTip, "SUCCESS",
                    null, null, null, null, null);
        }
    }

    /** Curator: TRUSTED SUCCESS → UPVOTE matching SUCCESS heuristics or ADD positive tip. */
    private void curateFromTrustedSuccess() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, tenant_id, category, change_type, json_path,
                   source_service, target_service, endpoint
            FROM error_precedents
            WHERE quality = 'TRUSTED' AND outcome = 'SUCCESS'
            ORDER BY verified_at DESC NULLS LAST
            LIMIT ?
            """, reflectLimit);

        for (Map<String, Object> row : rows) {
            String scope = TopologyScope.of(
                    str(row.get("source_service")),
                    str(row.get("target_service")),
                    str(row.get("endpoint")));
            if (scope == null) continue;

            String changeType = str(row.get("change_type"));
            String jsonPath = str(row.get("json_path"));
            String heuristic = "Prefer " + (changeType != null ? changeType : "structural mapping")
                    + (jsonPath != null ? " at " + jsonPath : "")
                    + " on this route — TRUSTED SUCCESS.";

            // ADD (or UPVOTE if duplicate)
            heuristicsService.apply(
                    RepairHeuristicsService.Op.ADD,
                    asUuid(row.get("tenant_id")),
                    scope,
                    heuristic,
                    "SUCCESS",
                    str(row.get("category")),
                    changeType,
                    null,
                    asUuid(row.get("id")),
                    null);
        }
    }

    static String synthesizeFromCritic(String critic, Map<String, Object> sig) {
        if (critic == null || critic.isBlank()) return null;
        String c = critic.trim();
        if (c.length() > 400) c = c.substring(0, 400) + "…";
        String path = sig != null ? str(sig.get("json_path")) : null;
        String change = sig != null ? str(sig.get("change_type")) : null;

        String lower = c.toLowerCase(Locale.ROOT);
        // Soft EDIT signal: if critic mentions rename vs coerce confusion, emit a WARN tip
        if (lower.contains("rename") && lower.contains("coerce")) {
            return "Do not confuse FIELD_RENAME with TYPE_COERCE"
                    + (path != null ? " near " + path : "")
                    + " — critic: " + c;
        }
        if (lower.contains("protected") || lower.contains("authorization")) {
            return "Never touch protected fields; critic flagged: " + c;
        }
        return "Critic warn-off"
                + (change != null ? " for " + change : "")
                + (path != null ? " at " + path : "")
                + ": " + c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        try {
            if (raw == null) return Map.of();
            if (raw instanceof Map<?, ?> m) {
                return new java.util.LinkedHashMap<>((Map<String, Object>) m);
            }
            if (raw instanceof String s && !s.isBlank()) {
                Object parsed = objectMapper.readValue(s, Object.class);
                if (parsed instanceof Map<?, ?> m) {
                    return new java.util.LinkedHashMap<>((Map<String, Object>) m);
                }
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private static UUID asUuid(Object o) {
        if (o == null) return null;
        try {
            return UUID.fromString(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
