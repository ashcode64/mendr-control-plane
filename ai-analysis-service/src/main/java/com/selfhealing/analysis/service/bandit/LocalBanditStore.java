package com.selfhealing.analysis.service.bandit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory True REx local posterior: arms are literal programs per incident session.
 * Global Beta lives in {@code bandit_state}; local updates never touch it.
 */
public final class LocalBanditStore {

    public record ProgramArm(
            String localArmId,
            String category,
            Map<String, Object> program,
            double alpha,
            double beta,
            int pulls,
            Boolean lastSuccess
    ) {
        ProgramArm withObservation(boolean success) {
            return new ProgramArm(
                    localArmId,
                    category,
                    program,
                    success ? alpha + 1.0 : alpha,
                    success ? beta : beta + 1.0,
                    pulls + 1,
                    success);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("localArmId", localArmId);
            m.put("category", category);
            m.put("program", program);
            m.put("alpha", alpha);
            m.put("beta", beta);
            m.put("pulls", pulls);
            m.put("lastSuccess", lastSuccess);
            return m;
        }
    }

    public static final class Session {
        public final String sessionId;
        public final UUID tenantId;
        public final List<String> allowedCategories;
        public final List<ProgramArm> arms = new ArrayList<>();
        public final long createdAtMs = System.currentTimeMillis();

        Session(String sessionId, UUID tenantId, List<String> allowedCategories) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.allowedCategories = List.copyOf(allowedCategories);
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMs;

    public LocalBanditStore(long ttlMs) {
        this.ttlMs = Math.max(60_000L, ttlMs);
    }

    public Session open(String sessionId, UUID tenantId, List<String> allowedCategories) {
        evictExpired();
        Session s = new Session(sessionId, tenantId, allowedCategories == null ? List.of() : allowedCategories);
        sessions.put(sessionId, s);
        return s;
    }

    public Session get(String sessionId) {
        if (sessionId == null) return null;
        Session s = sessions.get(sessionId);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.createdAtMs > ttlMs) {
            sessions.remove(sessionId);
            return null;
        }
        return s;
    }

    public ProgramArm register(
            String sessionId,
            String category,
            Map<String, Object> program,
            double seedAlpha,
            double seedBeta) {
        Session s = get(sessionId);
        if (s == null || program == null || program.isEmpty()) return null;
        String coerced = BanditCategory.coerceOrAbort(category, s.allowedCategories);
        if (coerced == null) return null;

        String armId = coerced + "#" + UUID.randomUUID().toString().substring(0, 8);
        ProgramArm arm = new ProgramArm(
                armId, coerced, Map.copyOf(program),
                Math.max(0.1, seedAlpha), Math.max(0.1, seedBeta),
                0, null);
        synchronized (s) {
            s.arms.add(arm);
        }
        return arm;
    }

    public ProgramArm observe(String sessionId, String localArmId, boolean success) {
        Session s = get(sessionId);
        if (s == null || localArmId == null) return null;
        synchronized (s) {
            for (int i = 0; i < s.arms.size(); i++) {
                ProgramArm a = s.arms.get(i);
                if (!localArmId.equals(a.localArmId())) continue;
                ProgramArm updated = a.withObservation(success);
                s.arms.set(i, updated);
                return updated;
            }
        }
        return null;
    }

    /** Thompson-sample among registered local program arms. */
    public ProgramArm thompsonPick(String sessionId, Random rng) {
        Session s = get(sessionId);
        if (s == null) return null;
        List<ProgramArm> snapshot;
        synchronized (s) {
            if (s.arms.isEmpty()) return null;
            snapshot = List.copyOf(s.arms);
        }
        ProgramArm best = null;
        double bestSample = -1;
        for (ProgramArm a : snapshot) {
            double sample = BanditService.sampleBeta(a.alpha(), a.beta(), rng);
            if (sample > bestSample) {
                bestSample = sample;
                best = a;
            }
        }
        return best;
    }

    public List<Map<String, Object>> snapshot(String sessionId) {
        Session s = get(sessionId);
        if (s == null) return List.of();
        synchronized (s) {
            return s.arms.stream().map(ProgramArm::toMap).toList();
        }
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().createdAtMs > ttlMs);
    }
}
