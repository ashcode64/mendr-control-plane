package com.selfhealing.analysis.service.safety;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * s₁ — generation confidence.
 *
 * <p>Prefer {@code exp(mean log p)} over token / changed-span logprobs when the
 * provider exposes them (Abstain &amp; Validate / LofreeCP). Else cluster-frequency
 * among resamples; else verbalized model confidence. Never invent logprobs.
 *
 * <p>Feature-vector index 0 in {@link LogisticNonconformityModel} (plan table s₁).
 */
public final class GenerationConfidence {

    private GenerationConfidence() {}

    public record Resolved(double value, String source, boolean fromLogprobs) {}

    /**
     * Resolve s₁ with plan priority: logprobs → clusterFreq (≥0) → verbalized.
     *
     * @param clusterFrequency {@code -1} when insufficient samples (do not use)
     */
    public static Resolved resolve(
            Object logprobsMeta,
            double clusterFrequency,
            double verbalized) {
        Double fromLp = fromLogprobs(logprobsMeta);
        if (fromLp != null) {
            return new Resolved(fromLp, "token_logprobs", true);
        }
        if (clusterFrequency >= 0.0) {
            return new Resolved(clamp01(clusterFrequency), "cluster_frequency", false);
        }
        return new Resolved(clamp01(verbalized), "verbalized", false);
    }

    /** {@code exp(mean log p)} for a list of per-token log-probabilities. */
    public static Double fromLogprobs(Object meta) {
        if (meta == null) return null;
        if (meta instanceof Number n) {
            double v = n.doubleValue();
            // Already a mean log-prob (≤0) or a probability in (0,1].
            if (v <= 0.0) return clamp01(Math.exp(v));
            if (v <= 1.0) return clamp01(v);
            return null;
        }
        if (meta instanceof Map<?, ?> m) {
            Object mean = m.get("meanLogProb");
            if (mean == null) mean = m.get("mean_logprob");
            if (mean instanceof Number n) {
                return clamp01(Math.exp(n.doubleValue()));
            }
            Object nested = m.get("tokenLogprobs");
            if (nested == null) nested = m.get("logprobs");
            if (nested == null) nested = m.get("tokens");
            if (nested == null) nested = m.get("content");
            return fromLogprobs(nested);
        }
        if (meta instanceof Collection<?> col) {
            List<Double> lps = new ArrayList<>();
            for (Object item : col) {
                if (item instanceof Number n) {
                    lps.add(n.doubleValue());
                } else if (item instanceof Map<?, ?> tm) {
                    Object lp = tm.get("logprob");
                    if (lp == null) lp = tm.get("log_prob");
                    if (lp == null) lp = tm.get("lp");
                    if (lp instanceof Number n) lps.add(n.doubleValue());
                }
            }
            if (lps.isEmpty()) return null;
            double sum = 0.0;
            for (double lp : lps) sum += lp;
            return clamp01(Math.exp(sum / lps.size()));
        }
        return null;
    }

    /** Pull logprobs blob from diagnose / rule metadata if present. */
    public static Object extractMeta(Map<String, Object> rules, Map<String, Object> analysisMeta) {
        if (rules != null) {
            for (String k : List.of("_tokenLogprobs", "tokenLogprobs", "_generationLogprobs",
                    "generationLogprobs", "changedSpanLogprobs")) {
                if (rules.get(k) != null) return rules.get(k);
            }
        }
        if (analysisMeta != null) {
            for (String k : List.of("tokenLogprobs", "generationLogprobs", "changedSpanLogprobs",
                    "logprobs")) {
                if (analysisMeta.get(k) != null) return analysisMeta.get(k);
            }
        }
        return null;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
