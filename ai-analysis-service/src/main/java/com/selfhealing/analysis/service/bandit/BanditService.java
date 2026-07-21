package com.selfhealing.analysis.service.bandit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hierarchical gated bandit (Phase 8.3c): global Beta(α,β) by strategy category,
 * local ≤3 REx arms seeded from global prior. Global credit is async only
 * (Approve → pending; quality lifecycle → credit/debit).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BanditService {

    public static final List<String> CATEGORIES = List.of(
            "STRUCTURAL_MAPPING", "DATA_COERCION", "ADD_DEFAULT",
            "FIELD_REMOVE", "RESPONSE_MAP", "ROUTING", "CORS");

    private final JdbcTemplate jdbcTemplate;

    @Value("${mendr.bandit.enabled:true}")
    private boolean enabled;

    @Value("${mendr.bandit.only-ambiguous:true}")
    private boolean onlyAmbiguous;

    @Value("${mendr.bandit.candidates:3}")
    private int candidates;

    public static String mapChangeTypeToCategory(String changeType) {
        if (changeType == null) return "STRUCTURAL_MAPPING";
        String u = changeType.toUpperCase();
        if (u.contains("COERCE") || u.contains("TYPE")) return "DATA_COERCION";
        if (u.contains("DEFAULT") || u.contains("ADD")) return "ADD_DEFAULT";
        if (u.contains("REMOVE")) return "FIELD_REMOVE";
        if (u.contains("RESPONSE")) return "RESPONSE_MAP";
        if (u.contains("ROUTING")) return "ROUTING";
        if (u.contains("CORS")) return "CORS";
        return "STRUCTURAL_MAPPING";
    }

    public boolean shouldEngage(boolean ambiguousAgentLoop) {
        if (!enabled) return false;
        return !onlyAmbiguous || ambiguousAgentLoop;
    }

    /** Thompson-sample up to {@code candidates} distinct categories. */
    public List<Map<String, Object>> selectLocalArms(UUID tenantId, List<String> preferred) {
        if (!enabled) return List.of();
        List<String> cats = preferred == null || preferred.isEmpty()
                ? new ArrayList<>(CATEGORIES)
                : new ArrayList<>(preferred);
        List<Scored> scored = new ArrayList<>();
        Random rng = ThreadLocalRandom.current();
        for (String cat : cats) {
            double[] ab = loadBeta(tenantId, cat);
            double sample = sampleBeta(ab[0], ab[1], rng);
            scored.add(new Scored(cat, sample, ab[0], ab[1]));
        }
        scored.sort(Comparator.comparingDouble(Scored::sample).reversed());
        int n = Math.min(candidates, scored.size());
        List<Map<String, Object>> arms = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Scored s = scored.get(i);
            Map<String, Object> arm = new LinkedHashMap<>();
            arm.put("category", s.category());
            arm.put("thompson", s.sample());
            arm.put("alpha", s.alpha());
            arm.put("beta", s.beta());
            arm.put("localArmId", s.category() + "#" + i);
            arms.add(arm);
        }
        return arms;
    }

    /** Enqueue pending credit on human Approve — never sync-update global Beta here. */
    public void enqueuePendingCredit(UUID tenantId, UUID analysisId, String category, String localArmId) {
        if (category == null || category.isBlank()) return;
        try {
            jdbcTemplate.update("""
                INSERT INTO bandit_pending_credit (tenant_id, analysis_id, category, local_arm_id, status)
                VALUES (?::uuid, ?::uuid, ?, ?, 'PENDING')
                """,
                    tenantId == null ? null : tenantId.toString(),
                    analysisId == null ? null : analysisId.toString(),
                    category,
                    localArmId);
        } catch (Exception e) {
            log.debug("bandit pending credit skipped: {}", e.getMessage());
        }
    }

    /** Apply async credit/debit after Wilson quality lifecycle promote/demote. */
    public void resolvePending(UUID analysisId, boolean success) {
        try {
            List<Map<String, Object>> pending = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, category FROM bandit_pending_credit
                WHERE analysis_id = ?::uuid AND status = 'PENDING'
                """, analysisId == null ? null : analysisId.toString());
            for (Map<String, Object> row : pending) {
                String cat = String.valueOf(row.get("category"));
                UUID tenant = row.get("tenant_id") == null ? null
                        : UUID.fromString(row.get("tenant_id").toString());
                if (success) {
                    credit(tenant, cat, true);
                    jdbcTemplate.update("""
                        UPDATE bandit_pending_credit SET status = 'CREDITED', resolved_at = NOW()
                        WHERE id = ?::uuid
                        """, row.get("id").toString());
                } else {
                    credit(tenant, cat, false);
                    jdbcTemplate.update("""
                        UPDATE bandit_pending_credit SET status = 'DEBITED', resolved_at = NOW()
                        WHERE id = ?::uuid
                        """, row.get("id").toString());
                }
            }
        } catch (Exception e) {
            log.debug("bandit resolve pending skipped: {}", e.getMessage());
        }
    }

    public void credit(UUID tenantId, String category, boolean success) {
        ensureRow(tenantId, category);
        try {
            if (success) {
                jdbcTemplate.update("""
                    UPDATE bandit_state SET alpha = alpha + 1, pulls = pulls + 1, updated_at = NOW()
                    WHERE category = ? AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                    """, category, tenantId == null ? null : tenantId.toString());
            } else {
                jdbcTemplate.update("""
                    UPDATE bandit_state SET beta = beta + 1, pulls = pulls + 1, updated_at = NOW()
                    WHERE category = ? AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                    """, category, tenantId == null ? null : tenantId.toString());
            }
        } catch (Exception e) {
            log.debug("bandit credit skipped: {}", e.getMessage());
        }
    }

    private void ensureRow(UUID tenantId, String category) {
        try {
            Integer exists = jdbcTemplate.query(
                    """
                    SELECT COUNT(*)::int FROM bandit_state
                    WHERE category = ? AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                    """,
                    rs -> rs.next() ? rs.getInt(1) : 0,
                    category, tenantId == null ? null : tenantId.toString());
            if (exists != null && exists > 0) return;
            jdbcTemplate.update("""
                INSERT INTO bandit_state (tenant_id, category, alpha, beta)
                VALUES (?::uuid, ?, 1.0, 1.0)
                """, tenantId == null ? null : tenantId.toString(), category);
        } catch (Exception ignored) {
            // table may not exist yet on fresh volume without init_v6
        }
    }

    private double[] loadBeta(UUID tenantId, String category) {
        ensureRow(tenantId, category);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT alpha, beta FROM bandit_state
                WHERE category = ? AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                LIMIT 1
                """, category, tenantId == null ? null : tenantId.toString());
            if (rows.isEmpty()) return new double[]{1.0, 1.0};
            return new double[]{
                    ((Number) rows.get(0).get("alpha")).doubleValue(),
                    ((Number) rows.get(0).get("beta")).doubleValue()
            };
        } catch (Exception e) {
            return new double[]{1.0, 1.0};
        }
    }

    /** Gamma-ratio Beta sample. */
    static double sampleBeta(double a, double b, Random rng) {
        double x = sampleGamma(a, rng);
        double y = sampleGamma(b, rng);
        if (x + y == 0) return 0.5;
        return x / (x + y);
    }

    static double sampleGamma(double shape, Random rng) {
        if (shape < 1.0) {
            return sampleGamma(1.0 + shape, rng) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x, v;
            do {
                x = rng.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1.0 - 0.0331 * (x * x) * (x * x)) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) return d * v;
        }
    }

    private record Scored(String category, double sample, double alpha, double beta) {}
}
