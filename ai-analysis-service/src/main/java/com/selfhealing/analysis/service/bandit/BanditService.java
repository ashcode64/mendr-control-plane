package com.selfhealing.analysis.service.bandit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * True REx hierarchical bandit (Phase 4):
 * <ul>
 *   <li>Global arms = fixed category tags in {@code bandit_state} (async Wilson credit only)</li>
 *   <li>Local arms = literal MendrScript programs (in-memory Beta per incident session)</li>
 * </ul>
 * Never sync-updates global Beta at Approve — only {@link #enqueuePendingCredit}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BanditService {

    /** @deprecated use {@link BanditCategory#ALL} */
    public static final List<String> CATEGORIES = BanditCategory.ALL;

    private final JdbcTemplate jdbcTemplate;

    private LocalBanditStore localStore;

    @Value("${mendr.bandit.enabled:true}")
    private boolean enabled;

    @Value("${mendr.bandit.only-ambiguous:true}")
    private boolean onlyAmbiguous;

    @Value("${mendr.bandit.candidates:3}")
    private int candidates;

    @Value("${mendr.bandit.local-ttl-ms:3600000}")
    private long localTtlMs;

    @PostConstruct
    void initLocalStore() {
        localStore = new LocalBanditStore(localTtlMs);
    }

    /** Visible for tests. */
    LocalBanditStore localStore() {
        if (localStore == null) localStore = new LocalBanditStore(localTtlMs);
        return localStore;
    }

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

    /**
     * Thompson-sample ≤{@code candidates} distinct categories and open a local session
     * seeded from global (α,β). Local program arms are registered later via
     * {@link #registerLocalProgram}.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> selectLocalArms(UUID tenantId, List<String> preferred) {
        Map<String, Object> session = openSession(tenantId, preferred, null);
        Object arms = session.get("arms");
        if (arms instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
            return out;
        }
        return List.of();
    }

    /** Open True REx session: category arms + sessionId for local program posterior. */
    public Map<String, Object> openSession(UUID tenantId, List<String> preferred, String sessionIdHint) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engaged", false);
        out.put("arms", List.of());
        if (!enabled) {
            out.put("reason", "disabled");
            return out;
        }

        List<String> cats = preferred == null || preferred.isEmpty()
                ? new ArrayList<>(BanditCategory.ALL)
                : preferred.stream()
                    .map(BanditCategory::normalize)
                    .filter(c -> c != null)
                    .distinct()
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (cats.isEmpty()) cats = new ArrayList<>(BanditCategory.ALL);

        List<Scored> scored = new ArrayList<>();
        Random rng = ThreadLocalRandom.current();
        for (String cat : cats) {
            double[] ab = loadBeta(tenantId, cat);
            double sample = sampleBeta(ab[0], ab[1], rng);
            scored.add(new Scored(cat, sample, ab[0], ab[1]));
        }
        scored.sort(Comparator.comparingDouble(Scored::sample).reversed());
        int n = Math.min(Math.max(1, candidates), scored.size());

        List<Map<String, Object>> arms = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Scored s = scored.get(i);
            Map<String, Object> arm = new LinkedHashMap<>();
            arm.put("category", s.category());
            arm.put("thompson", s.sample());
            arm.put("alpha", s.alpha());
            arm.put("beta", s.beta());
            arm.put("slot", i);
            // Category-level placeholder id (programs get real localArmIds on register)
            arm.put("localArmId", s.category() + "#cat" + i);
            arms.add(arm);
            allowed.add(s.category());
        }

        String sessionId = (sessionIdHint != null && !sessionIdHint.isBlank())
                ? sessionIdHint
                : UUID.randomUUID().toString();
        localStore().open(sessionId, tenantId, allowed);

        out.put("engaged", true);
        out.put("sessionId", sessionId);
        out.put("arms", arms);
        out.put("allowedCategories", allowed);
        if (!arms.isEmpty()) {
            out.put("category", arms.get(0).get("category"));
            out.put("selected", arms.get(0));
        }
        return out;
    }

    public Map<String, Object> registerLocalProgram(
            String sessionId,
            String category,
            Map<String, Object> program) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalBanditStore.Session session = localStore().get(sessionId);
        if (session == null) {
            out.put("registered", false);
            out.put("error", "unknown_or_expired_session");
            return out;
        }
        String coerced = BanditCategory.coerceOrAbort(category, session.allowedCategories);
        if (coerced == null) {
            out.put("registered", false);
            out.put("error", "category_aborted");
            out.put("rawCategory", category);
            out.put("allowedCategories", session.allowedCategories);
            return out;
        }
        double[] ab = loadBeta(session.tenantId, coerced);
        LocalBanditStore.ProgramArm arm = localStore().register(
                sessionId, coerced, program, ab[0], ab[1]);
        if (arm == null) {
            out.put("registered", false);
            out.put("error", "register_failed");
            return out;
        }
        out.put("registered", true);
        out.put("arm", arm.toMap());
        out.put("coercedCategory", coerced);
        return out;
    }

    public Map<String, Object> observeLocal(String sessionId, String localArmId, boolean success) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalBanditStore.ProgramArm arm = localStore().observe(sessionId, localArmId, success);
        if (arm == null) {
            out.put("updated", false);
            out.put("error", "arm_not_found");
            return out;
        }
        out.put("updated", true);
        out.put("arm", arm.toMap());
        // Local only — never touch bandit_state here
        out.put("globalUpdated", false);
        return out;
    }

    public Map<String, Object> pickLocal(String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalBanditStore.ProgramArm arm = localStore().thompsonPick(
                sessionId, ThreadLocalRandom.current());
        if (arm == null) {
            out.put("picked", false);
            out.put("error", "no_local_arms");
            return out;
        }
        out.put("picked", true);
        out.put("arm", arm.toMap());
        out.put("category", arm.category());
        out.put("localArmId", arm.localArmId());
        out.put("program", arm.program());
        return out;
    }

    public List<Map<String, Object>> localSnapshot(String sessionId) {
        return localStore().snapshot(sessionId);
    }

    /**
     * Enqueue pending credit on human Approve — never sync-update global Beta here.
     * Invalid / non-enum categories are refused (Phase 4 guardrail).
     */
    public boolean enqueuePendingCredit(UUID tenantId, UUID analysisId, String category, String localArmId) {
        String norm = BanditCategory.normalize(category);
        if (norm == null) {
            log.warn("bandit pending credit aborted: invalid category={}", category);
            return false;
        }
        try {
            jdbcTemplate.update("""
                INSERT INTO bandit_pending_credit (tenant_id, analysis_id, category, local_arm_id, status)
                VALUES (?::uuid, ?::uuid, ?, ?, 'PENDING')
                """,
                    tenantId == null ? null : tenantId.toString(),
                    analysisId == null ? null : analysisId.toString(),
                    norm,
                    localArmId);
            return true;
        } catch (Exception e) {
            log.debug("bandit pending credit skipped: {}", e.getMessage());
            return false;
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
                String cat = BanditCategory.normalize(String.valueOf(row.get("category")));
                if (cat == null) {
                    jdbcTemplate.update("""
                        UPDATE bandit_pending_credit SET status = 'DEBITED', resolved_at = NOW()
                        WHERE id = ?::uuid
                        """, row.get("id").toString());
                    continue;
                }
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
        String norm = BanditCategory.normalize(category);
        if (norm == null) return;
        ensureRow(tenantId, norm);
        try {
            if (success) {
                jdbcTemplate.update("""
                    UPDATE bandit_state SET alpha = alpha + 1, pulls = pulls + 1, updated_at = NOW()
                    WHERE category = ? AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                    """, norm, tenantId == null ? null : tenantId.toString());
            } else {
                jdbcTemplate.update("""
                    UPDATE bandit_state SET beta = beta + 1, pulls = pulls + 1, updated_at = NOW()
                    WHERE category = ? AND (tenant_id IS NOT DISTINCT FROM ?::uuid)
                    """, norm, tenantId == null ? null : tenantId.toString());
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
