package com.selfhealing.analysis.service.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Phase 3a LILO skill library: stitch-compress repeat TRUSTED SUCCESS structural macros,
 * AutoDoc, RegressionHarness-gated promotion, sketch match for deterministic fast-path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillLibraryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RegressionHarnessService regressionHarness;

    @Value("${mendr.skills.min-support:2}")
    private int minSupport;

    @Value("${mendr.skills.distill-limit:80}")
    private int distillLimit;

    @Value("${mendr.skills.max-fetch:8}")
    private int maxFetch;

    /**
     * Match an active skill for the current sketch. Returns best hit or empty.
     */
    public Map<String, Object> match(
            UUID tenantId,
            String changeType,
            String category,
            List<String> allowedOpcodes,
            String targetPath) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, skill_key, name, autodoc, program, sketch_match,
                       change_type, category, support_count, hit_count
                FROM skill_library
                WHERE active = true
                  AND (tenant_id IS NULL OR tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND (? IS NULL OR change_type IS NULL OR change_type = ?)
                ORDER BY support_count DESC, hit_count DESC, updated_at DESC
                LIMIT 40
                """,
                    tenantId == null ? null : tenantId.toString(),
                    changeType, changeType);

            for (Map<String, Object> row : rows) {
                Map<String, Object> sketchMatch = asMap(row.get("sketch_match"));
                if (!SkillFingerprint.sketchCompatible(sketchMatch, changeType, allowedOpcodes)) {
                    continue;
                }
                Object programRaw = parseJson(row.get("program"));
                Map<String, Object> program = SkillFingerprint.instantiate(programRaw, targetPath);
                if (program.isEmpty()) continue;

                bumpHit(row.get("id"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", row.get("id"));
                out.put("skillKey", row.get("skill_key"));
                out.put("name", row.get("name"));
                out.put("autodoc", row.get("autodoc"));
                out.put("program", program);
                out.put("changeType", row.get("change_type"));
                out.put("category", row.get("category"));
                out.put("supportCount", row.get("support_count"));
                out.put("matched", true);
                return out;
            }
        } catch (Exception e) {
            log.debug("skill match skipped: {}", e.getMessage());
        }
        return Map.of("matched", false);
    }

    public List<Map<String, Object>> listActive(UUID tenantId, String changeType, String category) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, skill_key, name, autodoc, program, sketch_match,
                       change_type, category, support_count, hit_count
                FROM skill_library
                WHERE active = true
                  AND (tenant_id IS NULL OR tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND (? IS NULL OR change_type IS NULL OR change_type = ?)
                  AND (? IS NULL OR category IS NULL OR category = ?)
                ORDER BY support_count DESC, hit_count DESC
                LIMIT ?
                """,
                    tenantId == null ? null : tenantId.toString(),
                    changeType, changeType,
                    category, category,
                    maxFetch);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("skillKey", row.get("skill_key"));
                item.put("name", row.get("name"));
                item.put("autodoc", row.get("autodoc"));
                item.put("program", parseJson(row.get("program")));
                item.put("changeType", row.get("change_type"));
                item.put("category", row.get("category"));
                item.put("supportCount", row.get("support_count"));
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            log.debug("skill list skipped: {}", e.getMessage());
            return List.of();
        }
    }

    @Scheduled(fixedDelayString = "${mendr.skills.distill-ms:600000}")
    public void distillFromTrusted() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, category, change_type, json_path, program
                FROM error_precedents
                WHERE quality = 'TRUSTED' AND outcome = 'SUCCESS'
                  AND archived_at IS NULL
                  AND program IS NOT NULL
                ORDER BY verified_at DESC NULLS LAST
                LIMIT ?
                """, distillLimit);

            // Group by tenant + structural fingerprint
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Object program = parseJson(row.get("program"));
                String key = SkillFingerprint.of(program, str(row.get("change_type")));
                String tenant = row.get("tenant_id") == null ? "global" : row.get("tenant_id").toString();
                String groupKey = tenant + "::" + key;
                groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(row);
            }

            for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
                List<Map<String, Object>> cluster = e.getValue();
                if (cluster.size() < minSupport) continue;
                Map<String, Object> exemplar = cluster.get(0);
                Object program = parseJson(exemplar.get("program"));
                String changeType = str(exemplar.get("change_type"));
                String category = str(exemplar.get("category"));
                String jsonPath = str(exemplar.get("json_path"));
                String skillKey = SkillFingerprint.of(program, changeType);
                String autodoc = SkillFingerprint.autoDoc(program, changeType, category, jsonPath);
                Map<String, Object> sketchMatch = SkillFingerprint.sketchMatchPayload(program, changeType);

                UUID tenant = asUuid(exemplar.get("tenant_id"));
                List<UUID> sourceIds = new ArrayList<>();
                for (Map<String, Object> r : cluster) {
                    UUID id = asUuid(r.get("id"));
                    if (id != null) sourceIds.add(id);
                }
                promoteOrBump(tenant, skillKey, autodoc, program, sketchMatch,
                        changeType, category, cluster.size(), sourceIds);
            }
        } catch (Exception e) {
            log.debug("skill distill skipped: {}", e.getMessage());
        }
    }

    private void promoteOrBump(
            UUID tenantId,
            String skillKey,
            String autodoc,
            Object program,
            Map<String, Object> sketchMatch,
            String changeType,
            String category,
            int support,
            List<UUID> sourceIds) {
        try {
            Integer existing = jdbcTemplate.query("""
                SELECT support_count FROM skill_library
                WHERE skill_key = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND active = true
                LIMIT 1
                """,
                    rs -> rs.next() ? rs.getInt(1) : null,
                    skillKey, tenantId == null ? null : tenantId.toString());

            if (existing != null) {
                jdbcTemplate.update("""
                    UPDATE skill_library
                    SET support_count = GREATEST(support_count, ?),
                        autodoc = ?,
                        updated_at = NOW()
                    WHERE skill_key = ?
                      AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                    """,
                        support, autodoc, skillKey,
                        tenantId == null ? null : tenantId.toString());
                return;
            }

            // New skill: RegressionHarness gate (fail-closed)
            RegressionHarnessService.HarnessReport report =
                    regressionHarness.gatePromotion("skill", skillKey);
            if (!report.passed()) {
                log.warn("LILO skill promotion blocked by RegressionHarness key={}", skillKey);
                return;
            }

            String programJson = objectMapper.writeValueAsString(program);
            String sketchJson = objectMapper.writeValueAsString(sketchMatch);
            String name = "skill:" + (changeType == null ? "structural" : changeType.toLowerCase());
            String sourceArr = sourceIds.isEmpty()
                    ? "{}"
                    : "{" + sourceIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("") + "}";
            jdbcTemplate.update("""
                INSERT INTO skill_library (
                    tenant_id, skill_key, name, autodoc, program, sketch_match,
                    change_type, category, support_count, harness_passed_at, source_precedent_ids
                ) VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, NOW(), ?::uuid[])
                ON CONFLICT DO NOTHING
                """,
                    tenantId == null ? null : tenantId.toString(),
                    skillKey, name, autodoc, programJson, sketchJson,
                    changeType, category, support, sourceArr);
        } catch (Exception e) {
            log.debug("skill promote failed: {}", e.getMessage());
        }
    }

    private void bumpHit(Object id) {
        if (id == null) return;
        try {
            jdbcTemplate.update("""
                UPDATE skill_library SET hit_count = hit_count + 1, updated_at = NOW()
                WHERE id = ?::uuid
                """, id.toString());
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Object parseJson(Object raw) {
        try {
            if (raw == null) return Map.of();
            if (raw instanceof Map || raw instanceof List) return raw;
            if (raw instanceof String s && !s.isBlank()) {
                return objectMapper.readValue(s, Object.class);
            }
            // PGobject etc.
            return objectMapper.readValue(raw.toString(), Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        Object parsed = parseJson(raw);
        if (parsed instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
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
