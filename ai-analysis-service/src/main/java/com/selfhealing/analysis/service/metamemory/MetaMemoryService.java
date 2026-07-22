package com.selfhealing.analysis.service.metamemory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3b Semantic Memory: cluster TRUSTED precedents → MetaMemory abstract rules →
 * archive covered episodes (keep newest keepAlive) to curb pgvector bloat.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaMemoryService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${mendr.metamemory.min-cluster:3}")
    private int minCluster;

    @Value("${mendr.metamemory.keep-alive:2}")
    private int keepAlive;

    @Value("${mendr.metamemory.distill-limit:120}")
    private int distillLimit;

    @Value("${mendr.metamemory.max-fetch:12}")
    private int maxFetch;

    public List<Map<String, Object>> fetchActive(
            UUID tenantId, String category, String changeType) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, cluster_key, rule_text, change_type, category,
                       json_path_prefix, episode_count
                FROM meta_memory
                WHERE active = true
                  AND (tenant_id IS NULL OR tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND (? IS NULL OR category IS NULL OR category = ?)
                  AND (? IS NULL OR change_type IS NULL OR change_type = ?)
                ORDER BY episode_count DESC, updated_at DESC
                LIMIT ?
                """,
                    tenantId == null ? null : tenantId.toString(),
                    category, category,
                    changeType, changeType,
                    maxFetch);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("clusterKey", row.get("cluster_key"));
                item.put("rule", row.get("rule_text"));
                item.put("changeType", row.get("change_type"));
                item.put("category", row.get("category"));
                item.put("jsonPathPrefix", row.get("json_path_prefix"));
                item.put("episodeCount", row.get("episode_count"));
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            log.debug("meta_memory fetch skipped: {}", e.getMessage());
            return List.of();
        }
    }

    @Scheduled(fixedDelayString = "${mendr.metamemory.distill-ms:900000}")
    public void evolveFromTrusted() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, category, change_type, json_path, verified_at
                FROM error_precedents
                WHERE quality = 'TRUSTED' AND outcome = 'SUCCESS'
                  AND archived_at IS NULL
                ORDER BY verified_at DESC NULLS LAST
                LIMIT ?
                """, distillLimit);

            Map<String, List<Map<String, Object>>> clusters = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String clusterKey = clusterKey(row);
                clusters.computeIfAbsent(clusterKey, k -> new ArrayList<>()).add(row);
            }

            for (Map.Entry<String, List<Map<String, Object>>> e : clusters.entrySet()) {
                List<Map<String, Object>> cluster = e.getValue();
                if (cluster.size() < minCluster) continue;

                Map<String, Object> head = cluster.get(0);
                UUID tenant = asUuid(head.get("tenant_id"));
                String changeType = str(head.get("change_type"));
                String category = str(head.get("category"));
                String pathPrefix = pathPrefix(str(head.get("json_path")));
                String rule = synthesizeRule(changeType, category, pathPrefix, cluster.size());

                UUID metaId = upsertRule(tenant, e.getKey(), rule, changeType, category,
                        pathPrefix, cluster.size());
                if (metaId == null) continue;

                archiveExcess(cluster, metaId);
            }
        } catch (Exception e) {
            log.debug("meta_memory evolve skipped: {}", e.getMessage());
        }
    }

    private UUID upsertRule(
            UUID tenantId,
            String clusterKey,
            String ruleText,
            String changeType,
            String category,
            String pathPrefix,
            int episodeCount) {
        try {
            UUID existing = jdbcTemplate.query("""
                SELECT id FROM meta_memory
                WHERE cluster_key = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                LIMIT 1
                """,
                    rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
                    clusterKey, tenantId == null ? null : tenantId.toString());

            if (existing != null) {
                jdbcTemplate.update("""
                    UPDATE meta_memory
                    SET rule_text = ?,
                        episode_count = GREATEST(episode_count, ?),
                        updated_at = NOW(),
                        active = true
                    WHERE id = ?::uuid
                    """, ruleText, episodeCount, existing.toString());
                return existing;
            }

            return jdbcTemplate.query("""
                INSERT INTO meta_memory (
                    tenant_id, cluster_key, rule_text, change_type, category,
                    json_path_prefix, episode_count
                ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                    rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
                    tenantId == null ? null : tenantId.toString(),
                    clusterKey, ruleText, changeType, category, pathPrefix, episodeCount);
        } catch (Exception e) {
            log.debug("meta_memory upsert failed: {}", e.getMessage());
            return null;
        }
    }

    /** Keep newest keepAlive episodes; archive the rest under meta_memory_id. */
    private void archiveExcess(List<Map<String, Object>> cluster, UUID metaId) {
        int keep = Math.max(1, keepAlive);
        for (int i = keep; i < cluster.size(); i++) {
            UUID id = asUuid(cluster.get(i).get("id"));
            if (id == null) continue;
            try {
                jdbcTemplate.update("""
                    UPDATE error_precedents
                    SET archived_at = COALESCE(archived_at, NOW()),
                        meta_memory_id = ?::uuid
                    WHERE id = ?::uuid
                      AND archived_at IS NULL
                    """, metaId.toString(), id.toString());
            } catch (Exception e) {
                log.debug("archive episode {} skipped: {}", id, e.getMessage());
            }
        }
    }

    static String clusterKey(Map<String, Object> row) {
        String tenant = row.get("tenant_id") == null ? "global" : row.get("tenant_id").toString();
        String ct = nz(str(row.get("change_type")), "*").toUpperCase(Locale.ROOT);
        String cat = nz(str(row.get("category")), "*").toUpperCase(Locale.ROOT);
        String prefix = pathPrefix(str(row.get("json_path")));
        return tenant + "|" + ct + "|" + cat + "|" + prefix;
    }

    static String pathPrefix(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) return "/";
        String p = jsonPath.trim();
        if (!p.startsWith("/")) p = "/" + p;
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        int last = p.lastIndexOf('/');
        if (last <= 0) return "/";
        String parent = p.substring(0, last);
        return parent.isEmpty() ? "/" : parent;
    }

    static String synthesizeRule(String changeType, String category, String pathPrefix, int n) {
        return "MetaMemory: when " + nz(category, "UNKNOWN")
                + " + " + nz(changeType, "structural")
                + " under " + nz(pathPrefix, "/")
                + ", prefer the proven structural macro (n=" + n + " TRUSTED episodes archived).";
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

    private static String nz(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
