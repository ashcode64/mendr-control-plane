package com.selfhealing.analysis.service.ace;

import com.selfhealing.analysis.service.regression.RegressionHarnessService;
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
import java.util.UUID;

/**
 * ACE evolving playbook: itemized SUCCESS / FAILURE bullets distilled from precedents.
 * Promotions are gated by {@link RegressionHarnessService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcePlaybookService {

    private final JdbcTemplate jdbcTemplate;
    private final RegressionHarnessService regressionHarness;

    @Value("${mendr.ace.max-bullets:24}")
    private int maxBullets;

    @Value("${mendr.ace.distill-limit:40}")
    private int distillLimit;

    public List<Map<String, Object>> fetchActive(UUID tenantId, String category, String changeType) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, bullet, outcome, category, change_type, votes, topology_scope
                FROM ace_playbook
                WHERE active = true
                  AND (tenant_id IS NULL OR tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND (? IS NULL OR category IS NULL OR category = ?)
                  AND (? IS NULL OR change_type IS NULL OR change_type = ?)
                ORDER BY
                  CASE outcome WHEN 'FAILURE' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END,
                  votes DESC,
                  updated_at DESC
                LIMIT ?
                """,
                    tenantId == null ? null : tenantId.toString(),
                    category, category,
                    changeType, changeType,
                    maxBullets);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("bullet", row.get("bullet"));
                item.put("outcome", row.get("outcome"));
                item.put("category", row.get("category"));
                item.put("changeType", row.get("change_type"));
                item.put("votes", row.get("votes"));
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            log.debug("ace playbook fetch skipped: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Upsert a bullet. New SUCCESS/FAILURE promotions must pass the regression harness
     * (fail-closed). Vote increments on existing bullets skip the gate.
     */
    public boolean upsertBullet(
            UUID tenantId,
            String bullet,
            String outcome,
            String category,
            String changeType,
            UUID sourcePrecedentId,
            boolean requireHarnessGate) {
        if (bullet == null || bullet.isBlank()) return false;
        String oc = outcome == null ? "SUCCESS" : outcome.toUpperCase();
        if (!List.of("SUCCESS", "FAILURE", "WARN").contains(oc)) oc = "SUCCESS";

        try {
            Integer existing = jdbcTemplate.query("""
                SELECT votes FROM ace_playbook
                WHERE md5(bullet) = md5(?)
                  AND outcome = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND active = true
                LIMIT 1
                """,
                    rs -> rs.next() ? rs.getInt(1) : null,
                    bullet.trim(), oc, tenantId == null ? null : tenantId.toString());
            if (existing != null) {
                // Vote-only path: skip harness gate
                jdbcTemplate.update("""
                    UPDATE ace_playbook
                    SET votes = votes + 1,
                        updated_at = NOW(),
                        category = COALESCE(?, category),
                        change_type = COALESCE(?, change_type)
                    WHERE md5(bullet) = md5(?)
                      AND outcome = ?
                      AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                    """,
                        category, changeType,
                        bullet.trim(), oc, tenantId == null ? null : tenantId.toString());
                return true;
            }

            // First promote of a new bullet — always harness-gated (fail-closed)
            if (requireHarnessGate) {
                RegressionHarnessService.HarnessReport report =
                        regressionHarness.gatePromotion("playbook", oc + ":" + bullet.hashCode());
                if (!report.passed()) {
                    log.warn("ACE playbook promotion blocked by RegressionHarness (failed={}/{})",
                            report.failed(), report.total());
                    return false;
                }
            }

            jdbcTemplate.update("""
                INSERT INTO ace_playbook (
                    tenant_id, bullet, outcome, category, change_type, source_precedent_id, votes
                ) VALUES (?::uuid, ?, ?, ?, ?, ?::uuid, 1)
                """,
                    tenantId == null ? null : tenantId.toString(),
                    bullet.trim(),
                    oc,
                    category,
                    changeType,
                    sourcePrecedentId == null ? null : sourcePrecedentId.toString());
            return true;
        } catch (Exception e) {
            log.debug("ace playbook upsert failed: {}", e.getMessage());
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${mendr.ace.distill-ms:600000}")
    public void distillFromPrecedents() {
        try {
            distillTrustedSuccess();
            distillRejectedFailures();
        } catch (Exception e) {
            log.debug("ACE distill skipped: {}", e.getMessage());
        }
    }

    private void distillTrustedSuccess() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, tenant_id, category, change_type, json_path, quality, outcome
            FROM error_precedents
            WHERE quality = 'TRUSTED' AND outcome = 'SUCCESS'
            ORDER BY verified_at DESC NULLS LAST
            LIMIT ?
            """, distillLimit);
        for (Map<String, Object> row : rows) {
            String bullet = "Prefer " + nz(row.get("change_type"), "structural")
                    + " when fixing similar "
                    + nz(row.get("category"), "UNKNOWN")
                    + (row.get("json_path") != null ? " at " + row.get("json_path") : "")
                    + " — TRUSTED SUCCESS precedent.";
            UUID tenant = row.get("tenant_id") == null ? null
                    : UUID.fromString(row.get("tenant_id").toString());
            UUID precId = row.get("id") == null ? null
                    : UUID.fromString(row.get("id").toString());
            // First promote gated; duplicate bullets take vote-only path inside upsertBullet
            upsertBullet(tenant, bullet, "SUCCESS",
                    str(row.get("category")), str(row.get("change_type")), precId, true);
        }
    }

    private void distillRejectedFailures() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, tenant_id, category, change_type, json_path, quality, outcome, demote_reason
            FROM error_precedents
            WHERE quality = 'REJECTED' OR outcome = 'FAILURE'
            ORDER BY verified_at DESC NULLS LAST
            LIMIT ?
            """, distillLimit);
        for (Map<String, Object> row : rows) {
            String reason = str(row.get("demote_reason"));
            String bullet = "Avoid " + nz(row.get("change_type"), "this strategy")
                    + " for " + nz(row.get("category"), "UNKNOWN")
                    + (row.get("json_path") != null ? " at " + row.get("json_path") : "")
                    + " — REJECTED/FAILURE precedent"
                    + (reason != null ? " (" + reason + ")" : "")
                    + ".";
            UUID tenant = row.get("tenant_id") == null ? null
                    : UUID.fromString(row.get("tenant_id").toString());
            UUID precId = row.get("id") == null ? null
                    : UUID.fromString(row.get("id").toString());
            upsertBullet(tenant, bullet, "FAILURE",
                    str(row.get("category")), str(row.get("change_type")), precId, true);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String nz(Object o, String def) {
        return o == null || o.toString().isBlank() ? def : o.toString();
    }
}
