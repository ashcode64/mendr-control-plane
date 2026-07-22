package com.selfhealing.analysis.service.evolvemem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.regression.RegressionHarnessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 5 EvolveMem: versioned retrieval config, AutoMem relevance decay,
 * offline auto-tune, RegressionHarness-gated promote + revert on regression.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvolveMemService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RegressionHarnessService regressionHarness;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    @Value("${mendr.evolvemem.cache-ms:60000}")
    private long cacheMs;

    @Value("${mendr.evolvemem.tune-enabled:true}")
    private boolean tuneEnabled;

    private record Cached(RetrievalConfig config, long loadedAtMs) {}

    public RetrievalConfig activeConfig(UUID tenantId) {
        String key = tenantId == null ? "global" : tenantId.toString();
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.loadedAtMs() < cacheMs) {
            return c.config();
        }
        RetrievalConfig loaded = loadActive(tenantId);
        if (loaded == null && tenantId != null) {
            loaded = loadActive(null); // fall back to global
        }
        if (loaded == null) {
            loaded = RetrievalConfig.defaults();
        }
        cache.put(key, new Cached(loaded, now));
        return loaded;
    }

    public void invalidateCache() {
        cache.clear();
    }

    /** Apply decay + distance/score gates; returns filtered/rescored list. */
    public List<Map<String, Object>> applyRetrievalPolicy(
            List<Map<String, Object>> scored,
            RetrievalConfig cfg) {
        if (scored == null || scored.isEmpty()) return List.of();
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : scored) {
            double distance = row.get("distance") instanceof Number n ? n.doubleValue() : 1.0;
            if (distance > cfg.maxDistance()) continue;

            double base = row.get("score") instanceof Number n ? n.doubleValue() : 0.0;
            Instant event = eventTime(row);
            double decay = cfg.decayMultiplier(event, now);
            double finalScore = base * decay;
            if (finalScore < cfg.minScore()) continue;

            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("score", finalScore);
            item.put("baseScore", base);
            item.put("decay", decay);
            item.put("retrievalVersion", cfg.version());
            out.add(item);
        }
        out.sort((a, b) -> Double.compare(
                b.get("score") instanceof Number n ? n.doubleValue() : 0,
                a.get("score") instanceof Number n ? n.doubleValue() : 0));
        int k = Math.max(1, cfg.topK());
        if (out.size() > k) {
            return new ArrayList<>(out.subList(0, k));
        }
        return out;
    }

    @Scheduled(fixedDelayString = "${mendr.evolvemem.tune-ms:3600000}")
    public void autoTuneAndGate() {
        if (!tuneEnabled) return;
        try {
            RetrievalConfig active = activeConfig(null);
            RetrievalConfig candidate = proposeOfflineCandidate(active);
            if (candidate == null) return;
            UUID id = insertCandidate(null, candidate, active.version(), "offline auto-tune");
            if (id == null) return;
            // Pass candidate UUID so artifact-aware harness can load the row
            promoteIfHarnessPasses(id, id.toString());
        } catch (Exception e) {
            log.debug("EvolveMem auto-tune skipped: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${mendr.evolvemem.health-ms:7200000}")
    public void healthCheckRevert() {
        try {
            RetrievalConfig active = activeConfig(null);
            if (active.id() == null || active.version() <= 1) return;
            RegressionHarnessService.HarnessReport report =
                    regressionHarness.gatePromotion("retrieval_config", "health:v" + active.version());
            if (!report.passed()) {
                log.warn("EvolveMem ACTIVE v{} failed harness — reverting", active.version());
                revertActive(null, active);
            }
        } catch (Exception e) {
            log.debug("EvolveMem health revert skipped: {}", e.getMessage());
        }
    }

    /**
     * Offline propose: nudge knobs from recent precedent age/distance stats.
     * Structural-only — no LLM.
     */
    RetrievalConfig proposeOfflineCandidate(RetrievalConfig active) {
        Map<String, Object> stats = collectOfflineStats();
        double avgAge = num(stats.get("avgAgeDays"), 14);
        Double avgDistObj = stats.get("avgDistance") instanceof Number n ? n.doubleValue() : null;
        int sampleN = (int) num(stats.get("n"), 0);
        if (sampleN < 5) {
            log.debug("EvolveMem tune skipped: insufficient samples n={}", sampleN);
            return null;
        }

        int topK = active.topK();
        double halfLife = active.decayHalfLifeDays();
        double maxDist = active.maxDistance();
        double minScore = active.minScore();

        // Older corpus → faster decay; far neighbors → loosen distance / raise K
        if (avgAge > halfLife) {
            halfLife = Math.max(7, halfLife * 0.85);
        } else if (avgAge < halfLife * 0.4) {
            halfLife = Math.min(90, halfLife * 1.1);
        }
        // Distance nudges only when we measured real pairwise avgDistance
        if (avgDistObj != null) {
            double avgDist = avgDistObj;
            if (avgDist > 0.55) {
                topK = Math.min(24, topK + 2);
                maxDist = Math.min(1.2, maxDist + 0.05);
                minScore = Math.max(0.05, minScore - 0.02);
            } else if (avgDist < 0.25) {
                topK = Math.max(4, topK - 1);
                maxDist = Math.max(0.5, maxDist - 0.05);
                minScore = Math.min(0.4, minScore + 0.02);
            }
        }

        // No-op if unchanged
        if (topK == active.topK()
                && almostEq(halfLife, active.decayHalfLifeDays())
                && almostEq(maxDist, active.maxDistance())
                && almostEq(minScore, active.minScore())) {
            return null;
        }

        Map<String, Object> metrics = new LinkedHashMap<>(stats);
        metrics.put("fromVersion", active.version());
        metrics.put("proposedTopK", topK);
        metrics.put("proposedHalfLife", halfLife);

        return new RetrievalConfig(
                null, active.tenantId(), active.version() + 1,
                topK, minScore, maxDist, halfLife, active.decayFloor(),
                "CANDIDATE", active.version(), metrics);
    }

    public boolean promoteIfHarnessPasses(UUID candidateId, String artifactId) {
        RegressionHarnessService.HarnessReport report =
                regressionHarness.gatePromotion("retrieval_config", artifactId);
        if (!report.passed()) {
            log.warn("EvolveMem promote blocked by RegressionHarness id={}", candidateId);
            return false;
        }
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT tenant_id, version FROM retrieval_config WHERE id = ?::uuid
                """, candidateId.toString());
            UUID tenant = row.get("tenant_id") == null ? null
                    : UUID.fromString(row.get("tenant_id").toString());
            int version = ((Number) row.get("version")).intValue();

            // Demote current ACTIVE → REVERTED lineage kept as prior ACTIVE becomes inactive
            jdbcTemplate.update("""
                UPDATE retrieval_config
                SET status = 'REVERTED', reverted_at = NOW()
                WHERE status = 'ACTIVE'
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                """, tenant == null ? null : tenant.toString());

            jdbcTemplate.update("""
                UPDATE retrieval_config
                SET status = 'ACTIVE',
                    activated_at = NOW(),
                    harness_passed_at = NOW()
                WHERE id = ?::uuid
                """, candidateId.toString());
            invalidateCache();
            log.info("EvolveMem promoted retrieval_config v{} ({})", version, candidateId);
            return true;
        } catch (Exception e) {
            log.debug("EvolveMem promote failed: {}", e.getMessage());
            return false;
        }
    }

    void revertActive(UUID tenantId, RetrievalConfig active) {
        try {
            Integer parent = active.parentVersion();
            if (parent == null || parent < 1) {
                // fall back to highest REVERTED/previous version
                parent = jdbcTemplate.query("""
                    SELECT MAX(version) FROM retrieval_config
                    WHERE version < ?
                      AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                    """,
                        rs -> rs.next() ? (Integer) rs.getObject(1) : null,
                        active.version(),
                        tenantId == null ? null : tenantId.toString());
            }
            if (parent == null) return;

            jdbcTemplate.update("""
                UPDATE retrieval_config
                SET status = 'REVERTED', reverted_at = NOW()
                WHERE id = ?::uuid
                """, active.id().toString());

            jdbcTemplate.update("""
                UPDATE retrieval_config
                SET status = 'ACTIVE', activated_at = NOW(), reverted_at = NULL
                WHERE version = ?
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                """, parent, tenantId == null ? null : tenantId.toString());
            invalidateCache();
            log.warn("EvolveMem reverted v{} → v{}", active.version(), parent);
        } catch (Exception e) {
            log.debug("EvolveMem revert failed: {}", e.getMessage());
        }
    }

    private UUID insertCandidate(UUID tenantId, RetrievalConfig c, Integer parentVersion, String notes) {
        try {
            String metricsJson = objectMapper.writeValueAsString(
                    c.metrics() == null ? Map.of() : c.metrics());
            return jdbcTemplate.query("""
                INSERT INTO retrieval_config (
                    tenant_id, version, top_k, min_score, max_distance,
                    decay_half_life_days, decay_floor, status, parent_version, metrics, notes
                ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, 'CANDIDATE', ?, ?::jsonb, ?)
                ON CONFLICT DO NOTHING
                RETURNING id
                """,
                    rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
                    tenantId == null ? null : tenantId.toString(),
                    c.version(),
                    c.topK(),
                    c.minScore(),
                    c.maxDistance(),
                    c.decayHalfLifeDays(),
                    c.decayFloor(),
                    parentVersion,
                    metricsJson,
                    notes);
        } catch (Exception e) {
            log.debug("EvolveMem insert candidate failed: {}", e.getMessage());
            return null;
        }
    }

    private RetrievalConfig loadActive(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, version, top_k, min_score, max_distance,
                       decay_half_life_days, decay_floor, status, parent_version, metrics
                FROM retrieval_config
                WHERE status = 'ACTIVE'
                  AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                ORDER BY version DESC
                LIMIT 1
                """, tenantId == null ? null : tenantId.toString());
            if (rows.isEmpty()) return null;
            return fromRow(rows.get(0));
        } catch (Exception e) {
            log.debug("retrieval_config load skipped: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> collectOfflineStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT
                  COUNT(*)::int AS n,
                  AVG(EXTRACT(EPOCH FROM (NOW() - COALESCE(verified_at, approved_at, created_at))) / 86400.0)
                    AS avg_age_days
                FROM error_precedents
                WHERE quality IN ('TRUSTED','CANDIDATE')
                  AND archived_at IS NULL
                  AND created_at > NOW() - INTERVAL '90 days'
                """);
            if (!rows.isEmpty()) {
                stats.put("n", rows.get(0).get("n"));
                stats.put("avgAgeDays", rows.get(0).get("avg_age_days"));
            }
            // Real pairwise cosine distance among a recent embedding sample (no hardcoded 0.4).
            try {
                List<Map<String, Object>> distRows = jdbcTemplate.queryForList("""
                    WITH sample AS (
                      SELECT id, embedding
                      FROM error_precedents
                      WHERE embedding IS NOT NULL
                        AND quality IN ('TRUSTED','CANDIDATE')
                        AND archived_at IS NULL
                        AND created_at > NOW() - INTERVAL '90 days'
                      ORDER BY created_at DESC
                      LIMIT 16
                    )
                    SELECT AVG(s1.embedding <=> s2.embedding) AS avg_distance,
                           COUNT(*)::int AS pairs
                    FROM sample s1
                    JOIN sample s2 ON s1.id < s2.id
                    """);
                if (!distRows.isEmpty() && distRows.get(0).get("avg_distance") != null
                        && distRows.get(0).get("pairs") instanceof Number p && p.intValue() > 0) {
                    stats.put("avgDistance", distRows.get(0).get("avg_distance"));
                    stats.put("distancePairs", p.intValue());
                }
            } catch (Exception distEx) {
                log.debug("EvolveMem avgDistance sample skipped: {}", distEx.getMessage());
            }
            List<Map<String, Object>> q = jdbcTemplate.queryForList("""
                SELECT
                  COUNT(*) FILTER (WHERE quality = 'TRUSTED')::float
                    / GREATEST(COUNT(*), 1) AS trusted_ratio
                FROM error_precedents
                WHERE archived_at IS NULL
                  AND created_at > NOW() - INTERVAL '90 days'
                """);
            if (!q.isEmpty()) stats.put("trustedRatio", q.get(0).get("trusted_ratio"));
        } catch (Exception e) {
            stats.put("n", 0);
            stats.put("avgAgeDays", 14);
        }
        return stats;
    }

    @SuppressWarnings("unchecked")
    private RetrievalConfig fromRow(Map<String, Object> row) {
        Map<String, Object> metrics = Map.of();
        try {
            Object raw = row.get("metrics");
            if (raw instanceof Map<?, ?> m) {
                metrics = new LinkedHashMap<>((Map<String, Object>) m);
            } else if (raw != null) {
                Object parsed = objectMapper.readValue(raw.toString(), Object.class);
                if (parsed instanceof Map<?, ?> m) {
                    metrics = new LinkedHashMap<>((Map<String, Object>) m);
                }
            }
        } catch (Exception ignored) {
        }
        return new RetrievalConfig(
                asUuid(row.get("id")),
                asUuid(row.get("tenant_id")),
                ((Number) row.get("version")).intValue(),
                ((Number) row.get("top_k")).intValue(),
                ((Number) row.get("min_score")).doubleValue(),
                ((Number) row.get("max_distance")).doubleValue(),
                ((Number) row.get("decay_half_life_days")).doubleValue(),
                ((Number) row.get("decay_floor")).doubleValue(),
                str(row.get("status")),
                row.get("parent_version") == null ? null : ((Number) row.get("parent_version")).intValue(),
                metrics);
    }

    private static Instant eventTime(Map<String, Object> row) {
        Object v = row.get("verified_at");
        if (v == null) v = row.get("approved_at");
        if (v == null) v = row.get("created_at");
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        try {
            return Instant.parse(v.toString().replace(' ', 'T') + (v.toString().contains("Z") ? "" : "Z"));
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(v.toString()).toInstant();
            } catch (Exception e2) {
                return null;
            }
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

    private static double num(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    private static boolean almostEq(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }
}
