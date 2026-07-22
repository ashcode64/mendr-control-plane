package com.selfhealing.analysis.service.heuristics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Topology-scoped repair heuristics with ExpeL curator ops:
 * ADD / UPVOTE / DOWNVOTE / EDIT. {@code topology_scope} is required.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairHeuristicsService {

    public enum Op { ADD, UPVOTE, DOWNVOTE, EDIT }

    private final JdbcTemplate jdbcTemplate;

    @Value("${mendr.heuristics.max-fetch:16}")
    private int maxFetch;

    public List<Map<String, Object>> fetchForTopology(
            UUID tenantId,
            String topologyScope,
            String category,
            String changeType) {
        if (topologyScope == null || topologyScope.isBlank()) {
            return List.of();
        }
        try {
            // Pull candidates for this tenant (or global) then filter by TopologyScope.matches
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, topology_scope, heuristic_text, outcome, category, change_type,
                       votes, last_op, updated_at
                FROM repair_heuristics
                WHERE active = true
                  AND (tenant_id IS NULL OR tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND (? IS NULL OR category IS NULL OR category = ?)
                  AND (? IS NULL OR change_type IS NULL OR change_type = ?)
                ORDER BY
                  CASE outcome WHEN 'FAILURE' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END,
                  votes DESC,
                  updated_at DESC
                LIMIT 80
                """,
                    tenantId == null ? null : tenantId.toString(),
                    category, category,
                    changeType, changeType);

            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String scope = str(row.get("topology_scope"));
                if (!TopologyScope.matches(scope, topologyScope)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("topologyScope", scope);
                item.put("heuristic", row.get("heuristic_text"));
                item.put("outcome", row.get("outcome"));
                item.put("category", row.get("category"));
                item.put("changeType", row.get("change_type"));
                item.put("votes", row.get("votes"));
                out.add(item);
                if (out.size() >= maxFetch) break;
            }
            return out;
        } catch (Exception e) {
            log.debug("repair heuristics fetch skipped: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Apply an ExpeL curator op. ADD/EDIT require non-blank topology_scope.
     * @return true if the op was applied
     */
    public boolean apply(
            Op op,
            UUID tenantId,
            String topologyScope,
            String heuristicText,
            String outcome,
            String category,
            String changeType,
            UUID sourceTraceId,
            UUID sourcePrecedentId,
            String newTextForEdit) {
        if (op == null) return false;
        return switch (op) {
            case ADD -> add(tenantId, topologyScope, heuristicText, outcome,
                    category, changeType, sourceTraceId, sourcePrecedentId);
            case UPVOTE -> vote(tenantId, topologyScope, heuristicText, outcome, +1);
            case DOWNVOTE -> vote(tenantId, topologyScope, heuristicText, outcome, -1);
            case EDIT -> edit(tenantId, topologyScope, heuristicText, newTextForEdit, outcome);
        };
    }

    private boolean add(
            UUID tenantId,
            String topologyScope,
            String heuristicText,
            String outcome,
            String category,
            String changeType,
            UUID sourceTraceId,
            UUID sourcePrecedentId) {
        if (topologyScope == null || topologyScope.isBlank()) {
            log.debug("ADD refused: topology_scope required");
            return false;
        }
        if (heuristicText == null || heuristicText.isBlank()) return false;
        String oc = normalizeOutcome(outcome);
        try {
            Integer existing = findVotes(tenantId, topologyScope, heuristicText, oc);
            if (existing != null) {
                return vote(tenantId, topologyScope, heuristicText, oc, +1);
            }
            jdbcTemplate.update("""
                INSERT INTO repair_heuristics (
                    tenant_id, topology_scope, heuristic_text, outcome,
                    category, change_type, source_trace_id, source_precedent_id,
                    votes, last_op
                ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, 1, 'ADD')
                """,
                    tenantId == null ? null : tenantId.toString(),
                    topologyScope.trim(),
                    heuristicText.trim(),
                    oc,
                    category,
                    changeType,
                    sourceTraceId == null ? null : sourceTraceId.toString(),
                    sourcePrecedentId == null ? null : sourcePrecedentId.toString());
            return true;
        } catch (Exception e) {
            log.debug("repair heuristic ADD failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean vote(
            UUID tenantId,
            String topologyScope,
            String heuristicText,
            String outcome,
            int delta) {
        if (topologyScope == null || heuristicText == null) return false;
        String oc = normalizeOutcome(outcome);
        try {
            int updated = jdbcTemplate.update("""
                UPDATE repair_heuristics
                SET votes = votes + ?,
                    last_op = ?,
                    updated_at = NOW(),
                    active = CASE WHEN votes + ? <= 0 THEN false ELSE true END
                WHERE topology_scope = ?
                  AND md5(heuristic_text) = md5(?)
                  AND outcome = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                """,
                    delta,
                    delta > 0 ? "UPVOTE" : "DOWNVOTE",
                    delta,
                    topologyScope.trim(),
                    heuristicText.trim(),
                    oc,
                    tenantId == null ? null : tenantId.toString());
            return updated > 0;
        } catch (Exception e) {
            log.debug("repair heuristic vote failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean edit(
            UUID tenantId,
            String topologyScope,
            String oldText,
            String newText,
            String outcome) {
        if (topologyScope == null || topologyScope.isBlank()
                || oldText == null || newText == null || newText.isBlank()) {
            return false;
        }
        String oc = normalizeOutcome(outcome);
        try {
            int updated = jdbcTemplate.update("""
                UPDATE repair_heuristics
                SET heuristic_text = ?,
                    last_op = 'EDIT',
                    updated_at = NOW()
                WHERE topology_scope = ?
                  AND md5(heuristic_text) = md5(?)
                  AND outcome = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND active = true
                """,
                    newText.trim(),
                    topologyScope.trim(),
                    oldText.trim(),
                    oc,
                    tenantId == null ? null : tenantId.toString());
            return updated > 0;
        } catch (Exception e) {
            log.debug("repair heuristic EDIT failed: {}", e.getMessage());
            return false;
        }
    }

    private Integer findVotes(UUID tenantId, String scope, String text, String outcome) {
        try {
            return jdbcTemplate.query("""
                SELECT votes FROM repair_heuristics
                WHERE topology_scope = ?
                  AND md5(heuristic_text) = md5(?)
                  AND outcome = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                LIMIT 1
                """,
                    rs -> rs.next() ? rs.getInt(1) : null,
                    scope.trim(), text.trim(), outcome,
                    tenantId == null ? null : tenantId.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) return "SUCCESS";
        String u = outcome.toUpperCase(Locale.ROOT);
        if (List.of("SUCCESS", "FAILURE", "WARN").contains(u)) return u;
        return "SUCCESS";
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
