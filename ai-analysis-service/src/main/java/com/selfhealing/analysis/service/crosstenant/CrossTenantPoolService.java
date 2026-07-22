package com.selfhealing.analysis.service.crosstenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.regression.RegressionHarnessService;
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
import java.util.UUID;

/**
 * Phase 7: publish anonymized skills/heuristics/playbook into the global pool,
 * and import into opt-in tenants only after local critic + RegressionHarness pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossTenantPoolService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CrossTenantGate gate;
    private final CrossTenantAnonymizer anonymizer;
    private final RegressionHarnessService regressionHarness;
    private final MendrScriptGatewayClient mendrScriptGatewayClient;

    @Value("${mendr.cross-tenant.publish-limit:40}")
    private int publishLimit;

    @Value("${mendr.cross-tenant.import-limit:20}")
    private int importLimit;

    @Value("${mendr.cross-tenant.max-fetch:8}")
    private int maxFetch;

    public Map<String, Object> fetchForImport(
            UUID tenantId,
            String artifactType,
            String changeType,
            String category) {
        if (!gate.canImport(tenantId)) {
            return Map.of(
                    "enabled", false,
                    "artifacts", List.of(),
                    "reason", gate.globallyEnabled() ? "tenant_not_opted_in" : "disabled");
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT p.id, p.artifact_type, p.fingerprint, p.change_type, p.category,
                       p.topology_scope, p.payload, p.support_count
                FROM cross_tenant_pool p
                WHERE p.status = 'PUBLISHED'
                  AND (? IS NULL OR p.artifact_type = ?)
                  AND (? IS NULL OR p.change_type IS NULL OR p.change_type = ?)
                  AND (? IS NULL OR p.category IS NULL OR p.category = ?)
                  AND NOT EXISTS (
                    SELECT 1 FROM cross_tenant_imports i
                    WHERE i.pool_id = p.id AND i.tenant_id = ?::uuid
                      AND i.status = 'ACCEPTED'
                  )
                ORDER BY p.support_count DESC, p.updated_at DESC
                LIMIT ?
                """,
                    artifactType, artifactType,
                    changeType, changeType,
                    category, category,
                    tenantId.toString(),
                    maxFetch);
            List<Map<String, Object>> arts = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("artifactType", row.get("artifact_type"));
                item.put("fingerprint", row.get("fingerprint"));
                item.put("changeType", row.get("change_type"));
                item.put("category", row.get("category"));
                item.put("topologyScope", row.get("topology_scope"));
                item.put("payload", parseJson(row.get("payload")));
                item.put("supportCount", row.get("support_count"));
                arts.add(item);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", true);
            out.put("artifacts", arts);
            return out;
        } catch (Exception e) {
            log.debug("cross-tenant fetch skipped: {}", e.getMessage());
            return Map.of("enabled", true, "artifacts", List.of(), "error", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${mendr.cross-tenant.publish-ms:900000}")
    public void publishFromOptInTenants() {
        if (!gate.globallyEnabled()) return;
        try {
            List<Map<String, Object>> tenants = jdbcTemplate.queryForList("""
                SELECT tenant_id FROM cross_tenant_opt_in
                WHERE publish_enabled = true AND privacy_reviewed_at IS NOT NULL
                """);
            for (Map<String, Object> t : tenants) {
                UUID tenantId = asUuid(t.get("tenant_id"));
                if (tenantId == null || !gate.canPublish(tenantId)) continue;
                publishSkills(tenantId);
                publishHeuristics(tenantId);
                publishPlaybook(tenantId);
            }
        } catch (Exception e) {
            log.debug("cross-tenant publish skipped: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${mendr.cross-tenant.import-ms:900000}")
    public void importForOptInTenants() {
        if (!gate.globallyEnabled()) return;
        try {
            List<Map<String, Object>> tenants = jdbcTemplate.queryForList("""
                SELECT tenant_id FROM cross_tenant_opt_in
                WHERE import_enabled = true AND privacy_reviewed_at IS NOT NULL
                """);
            for (Map<String, Object> t : tenants) {
                UUID tenantId = asUuid(t.get("tenant_id"));
                if (tenantId == null || !gate.canImport(tenantId)) continue;
                importBatch(tenantId);
            }
        } catch (Exception e) {
            log.debug("cross-tenant import skipped: {}", e.getMessage());
        }
    }

    /** Manual/single import attempt (also used by controller). */
    public Map<String, Object> importOne(UUID tenantId, UUID poolId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!gate.canImport(tenantId)) {
            out.put("imported", false);
            out.put("reason", "not_allowed");
            return out;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, artifact_type, fingerprint, change_type, category,
                       topology_scope, payload
                FROM cross_tenant_pool
                WHERE id = ?::uuid AND status = 'PUBLISHED'
                """, poolId.toString());
            if (rows.isEmpty()) {
                out.put("imported", false);
                out.put("reason", "not_found");
                return out;
            }
            return materialize(tenantId, rows.get(0));
        } catch (Exception e) {
            out.put("imported", false);
            out.put("reason", e.getMessage());
            return out;
        }
    }

    private void importBatch(UUID tenantId) {
        Map<String, Object> fetched = fetchForImport(tenantId, null, null, null);
        Object arts = fetched.get("artifacts");
        if (!(arts instanceof List<?> list)) return;
        int n = 0;
        for (Object o : list) {
            if (n >= importLimit) break;
            if (!(o instanceof Map<?, ?> m)) continue;
            Object id = m.get("id");
            if (id == null) continue;
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, artifact_type, fingerprint, change_type, category,
                           topology_scope, payload
                    FROM cross_tenant_pool WHERE id = ?::uuid
                    """, id.toString());
                if (!rows.isEmpty()) {
                    materialize(tenantId, rows.get(0));
                    n++;
                }
            } catch (Exception e) {
                log.debug("import {} failed: {}", id, e.getMessage());
            }
        }
    }

    private Map<String, Object> materialize(UUID tenantId, Map<String, Object> poolRow) {
        Map<String, Object> out = new LinkedHashMap<>();
        UUID poolId = asUuid(poolRow.get("id"));
        String type = str(poolRow.get("artifact_type"));
        Map<String, Object> payload = asMap(poolRow.get("payload"));

        boolean criticOk = localCriticPasses(type, payload);
        if (!criticOk) {
            recordImport(tenantId, poolId, null, "REJECTED", false, false, "local_critic_failed");
            out.put("imported", false);
            out.put("reason", "local_critic_failed");
            return out;
        }

        RegressionHarnessService.HarnessReport report =
                regressionHarness.gatePromotion("cross_tenant_" + type,
                        poolId == null ? type : poolId.toString());
        if (!report.passed()) {
            recordImport(tenantId, poolId, null, "REJECTED", true, false, "harness_failed");
            out.put("imported", false);
            out.put("reason", "harness_failed");
            return out;
        }

        UUID localId = switch (type) {
            case "skill" -> insertLocalSkill(tenantId, payload, poolRow);
            case "heuristic" -> insertLocalHeuristic(tenantId, payload, poolRow);
            case "playbook" -> insertLocalPlaybook(tenantId, payload, poolRow);
            default -> null;
        };
        if (localId == null) {
            recordImport(tenantId, poolId, null, "REJECTED", true, true, "materialize_failed");
            out.put("imported", false);
            out.put("reason", "materialize_failed");
            return out;
        }
        recordImport(tenantId, poolId, localId, "ACCEPTED", true, true, null);
        out.put("imported", true);
        out.put("localArtifactId", localId.toString());
        out.put("artifactType", type);
        return out;
    }

    private boolean localCriticPasses(String type, Map<String, Object> payload) {
        if ("skill".equals(type)) {
            Object program = payload.get("program");
            if (!(program instanceof Map<?, ?>)) return false;
            try {
                Map<String, Object> verify = mendrScriptGatewayClient.verify(program);
                return Boolean.TRUE.equals(verify.get("valid"));
            } catch (Exception e) {
                log.debug("skill critic failed: {}", e.getMessage());
                return false;
            }
        }
        // Heuristics / playbook: require scrubbed non-blank text, no raw emails/tokens leftovers
        String text = "heuristic".equals(type)
                ? str(payload.get("heuristic"))
                : str(payload.get("bullet"));
        if (text == null || text.isBlank() || text.length() < 12) return false;
        String lower = text.toLowerCase();
        if (lower.contains("@") && lower.contains(".")) return false; // unsanitized email risk
        return true;
    }

    private UUID insertLocalSkill(UUID tenantId, Map<String, Object> payload, Map<String, Object> poolRow) {
        try {
            String skillKey = "xt:" + str(poolRow.get("fingerprint"));
            if (skillKey.length() > 120) skillKey = skillKey.substring(0, 120);
            UUID existing = lookupSkillId(tenantId, skillKey);
            if (existing != null) return existing;

            String autodoc = str(payload.get("autodoc"));
            if (autodoc == null || autodoc.isBlank()) autodoc = "Imported cross-tenant skill";
            String programJson = objectMapper.writeValueAsString(payload.get("program"));
            String sketchJson = objectMapper.writeValueAsString(
                    payload.get("sketchMatch") == null ? Map.of() : payload.get("sketchMatch"));
            jdbcTemplate.update("""
                INSERT INTO skill_library (
                    tenant_id, skill_key, name, autodoc, program, sketch_match,
                    change_type, category, support_count, active, harness_passed_at
                ) VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 1, true, NOW())
                """,
                    tenantId.toString(),
                    skillKey,
                    "cross-tenant-import",
                    autodoc,
                    programJson,
                    sketchJson,
                    str(payload.get("changeType")),
                    str(payload.get("category")));
            return lookupSkillId(tenantId, skillKey);
        } catch (Exception e) {
            log.debug("insert local skill failed: {}", e.getMessage());
            return null;
        }
    }

    private UUID insertLocalHeuristic(UUID tenantId, Map<String, Object> payload, Map<String, Object> poolRow) {
        try {
            String scope = str(payload.get("topologyScope"));
            if (scope == null || scope.isBlank()) scope = "*/*/*";
            String text = str(payload.get("heuristic"));
            String outcome = str(payload.get("outcome"));
            if (outcome == null) outcome = "SUCCESS";

            List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                SELECT id FROM repair_heuristics
                WHERE tenant_id = ?::uuid
                  AND topology_scope = ?
                  AND md5(heuristic_text) = md5(?)
                  AND outcome = ?
                LIMIT 1
                """, tenantId.toString(), scope, text, outcome);
            if (!existing.isEmpty()) return asUuid(existing.get(0).get("id"));

            jdbcTemplate.update("""
                INSERT INTO repair_heuristics (
                    tenant_id, topology_scope, heuristic_text, outcome,
                    category, change_type, votes, last_op, active
                ) VALUES (?::uuid, ?, ?, ?, ?, ?, 1, 'ADD', true)
                """,
                    tenantId.toString(), scope, text, outcome,
                    str(payload.get("category")), str(payload.get("changeType")));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id FROM repair_heuristics
                WHERE tenant_id = ?::uuid AND md5(heuristic_text) = md5(?)
                ORDER BY created_at DESC LIMIT 1
                """, tenantId.toString(), text);
            return rows.isEmpty() ? null : asUuid(rows.get(0).get("id"));
        } catch (Exception e) {
            log.debug("insert local heuristic failed: {}", e.getMessage());
            return null;
        }
    }

    private UUID insertLocalPlaybook(UUID tenantId, Map<String, Object> payload, Map<String, Object> poolRow) {
        try {
            String bullet = str(payload.get("bullet"));
            String outcome = str(payload.get("outcome"));
            if (outcome == null) outcome = "SUCCESS";

            List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                SELECT id FROM ace_playbook
                WHERE tenant_id = ?::uuid
                  AND md5(bullet) = md5(?)
                  AND outcome = ?
                LIMIT 1
                """, tenantId.toString(), bullet, outcome);
            if (!existing.isEmpty()) return asUuid(existing.get(0).get("id"));

            jdbcTemplate.update("""
                INSERT INTO ace_playbook (
                    tenant_id, bullet, outcome, category, change_type, votes
                ) VALUES (?::uuid, ?, ?, ?, ?, 1)
                """,
                    tenantId.toString(), bullet, outcome,
                    str(payload.get("category")), str(payload.get("changeType")));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id FROM ace_playbook
                WHERE tenant_id = ?::uuid AND md5(bullet) = md5(?)
                ORDER BY created_at DESC LIMIT 1
                """, tenantId.toString(), bullet);
            return rows.isEmpty() ? null : asUuid(rows.get(0).get("id"));
        } catch (Exception e) {
            log.debug("insert local playbook failed: {}", e.getMessage());
            return null;
        }
    }

    private void publishSkills(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT skill_key, autodoc, program, sketch_match, change_type, category, support_count
                FROM skill_library
                WHERE active = true AND tenant_id = ?::uuid
                  AND harness_passed_at IS NOT NULL
                ORDER BY support_count DESC
                LIMIT ?
                """, tenantId.toString(), publishLimit);
            for (Map<String, Object> row : rows) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("skillKey", row.get("skill_key"));
                raw.put("autodoc", row.get("autodoc"));
                raw.put("program", parseJson(row.get("program")));
                raw.put("sketchMatch", parseJson(row.get("sketch_match")));
                raw.put("changeType", row.get("change_type"));
                raw.put("category", row.get("category"));
                Map<String, Object> scrubbed = anonymizer.scrubSkillPayload(raw);
                upsertPool("skill", scrubbed, str(row.get("change_type")), str(row.get("category")),
                        null, tenantId, intOr(row.get("support_count"), 1));
            }
        } catch (Exception e) {
            log.debug("publish skills skipped: {}", e.getMessage());
        }
    }

    private void publishHeuristics(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT topology_scope, heuristic_text, outcome, category, change_type, votes
                FROM repair_heuristics
                WHERE active = true AND tenant_id = ?::uuid AND votes > 0
                ORDER BY votes DESC
                LIMIT ?
                """, tenantId.toString(), publishLimit);
            for (Map<String, Object> row : rows) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("heuristic", row.get("heuristic_text"));
                raw.put("outcome", row.get("outcome"));
                raw.put("topologyScope", row.get("topology_scope"));
                raw.put("changeType", row.get("change_type"));
                raw.put("category", row.get("category"));
                Map<String, Object> scrubbed = anonymizer.scrubHeuristicPayload(raw);
                upsertPool("heuristic", scrubbed, str(row.get("change_type")), str(row.get("category")),
                        str(scrubbed.get("topologyScope")), tenantId, intOr(row.get("votes"), 1));
            }
        } catch (Exception e) {
            log.debug("publish heuristics skipped: {}", e.getMessage());
        }
    }

    private void publishPlaybook(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bullet, outcome, category, change_type, votes
                FROM ace_playbook
                WHERE active = true AND tenant_id = ?::uuid AND votes > 0
                ORDER BY votes DESC
                LIMIT ?
                """, tenantId.toString(), publishLimit);
            for (Map<String, Object> row : rows) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("bullet", row.get("bullet"));
                raw.put("outcome", row.get("outcome"));
                raw.put("changeType", row.get("change_type"));
                raw.put("category", row.get("category"));
                Map<String, Object> scrubbed = anonymizer.scrubPlaybookPayload(raw);
                upsertPool("playbook", scrubbed, str(row.get("change_type")), str(row.get("category")),
                        null, tenantId, intOr(row.get("votes"), 1));
            }
        } catch (Exception e) {
            log.debug("publish playbook skipped: {}", e.getMessage());
        }
    }

    private void upsertPool(
            String artifactType,
            Map<String, Object> payload,
            String changeType,
            String category,
            String topologyScope,
            UUID sourceTenant,
            int support) {
        try {
            String fp = anonymizer.fingerprint(artifactType, payload);
            String payloadJson = objectMapper.writeValueAsString(payload);
            String tenantHash = anonymizer.hashTenant(sourceTenant);
            jdbcTemplate.update("""
                INSERT INTO cross_tenant_pool (
                    artifact_type, fingerprint, change_type, category, topology_scope,
                    payload, source_tenant_hash, support_count, status
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'PUBLISHED')
                ON CONFLICT (artifact_type, fingerprint) DO UPDATE SET
                    support_count = GREATEST(cross_tenant_pool.support_count, EXCLUDED.support_count),
                    payload = EXCLUDED.payload,
                    updated_at = NOW(),
                    status = 'PUBLISHED'
                """,
                    artifactType, fp, changeType, category, topologyScope,
                    payloadJson, tenantHash, Math.max(1, support));
        } catch (Exception e) {
            log.debug("pool upsert skipped: {}", e.getMessage());
        }
    }

    private void recordImport(
            UUID tenantId,
            UUID poolId,
            UUID localId,
            String status,
            Boolean critic,
            Boolean harness,
            String reason) {
        if (tenantId == null || poolId == null) return;
        try {
            jdbcTemplate.update("""
                INSERT INTO cross_tenant_imports (
                    tenant_id, pool_id, local_artifact_id, status,
                    critic_passed, harness_passed, reject_reason, resolved_at
                ) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?,
                          CASE WHEN ? = 'PENDING' THEN NULL ELSE NOW() END)
                ON CONFLICT (tenant_id, pool_id) DO UPDATE SET
                    local_artifact_id = COALESCE(EXCLUDED.local_artifact_id, cross_tenant_imports.local_artifact_id),
                    status = EXCLUDED.status,
                    critic_passed = EXCLUDED.critic_passed,
                    harness_passed = EXCLUDED.harness_passed,
                    reject_reason = EXCLUDED.reject_reason,
                    resolved_at = EXCLUDED.resolved_at
                """,
                    tenantId.toString(),
                    poolId.toString(),
                    localId == null ? null : localId.toString(),
                    status,
                    critic,
                    harness,
                    reason,
                    status);
        } catch (Exception e) {
            log.debug("import record skipped: {}", e.getMessage());
        }
    }

    private UUID lookupSkillId(UUID tenantId, String skillKey) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id FROM skill_library
                WHERE skill_key = ? AND tenant_id = ?::uuid
                LIMIT 1
                """, skillKey, tenantId.toString());
            return rows.isEmpty() ? null : asUuid(rows.get(0).get("id"));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        Object parsed = parseJson(raw);
        if (parsed instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        return Map.of();
    }

    private Object parseJson(Object raw) {
        try {
            if (raw == null) return null;
            if (raw instanceof Map || raw instanceof List) return raw;
            if (raw instanceof String s) {
                if (s.isBlank()) return null;
                return objectMapper.readValue(s, Object.class);
            }
            return objectMapper.readValue(objectMapper.writeValueAsString(raw), Object.class);
        } catch (Exception e) {
            return null;
        }
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

    private static int intOr(Object o, int d) {
        return o instanceof Number n ? n.intValue() : d;
    }
}
