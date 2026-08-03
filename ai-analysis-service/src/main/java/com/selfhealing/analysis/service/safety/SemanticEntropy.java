package com.selfhealing.analysis.service.safety;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Semantic entropy over canonical AST clusters (Kuhn, Gal, Farquhar 2023),
 * adapted to MendrScript via {@link CanonicalAstHasher}.
 *
 * <p>{@code consistency = 1 - H / log(K)} ∈ [0,1]. Fewer than 2 candidates →
 * neutral {@code 0.5} (no evidence of agreement or disagreement).
 */
public final class SemanticEntropy {

    private SemanticEntropy() {}

    /**
     * @param clusterHashes one hash per candidate (already canonical / behavioral)
     * @return consistency in [0,1]; {@code 0.5} when fewer than 2 samples
     */
    public static double consistency(Collection<String> clusterHashes) {
        if (clusterHashes == null || clusterHashes.isEmpty()) return 0.5;
        Map<String, Integer> counts = new HashMap<>();
        int n = 0;
        for (String h : clusterHashes) {
            if (h == null || h.isBlank()) continue;
            counts.merge(h, 1, Integer::sum);
            n++;
        }
        // No resample evidence — do not claim perfect consistency.
        if (n < 2) return 0.5;
        int k = counts.size();
        if (k <= 1) return 1.0;
        double h = 0.0;
        for (int c : counts.values()) {
            double p = (double) c / n;
            if (p > 0) h -= p * Math.log(p);
        }
        double denom = Math.log(k);
        if (denom <= 1e-12) return 1.0;
        return clamp01(1.0 - h / denom);
    }

    /**
     * Frequency of the winning cluster among resamples — logit-free s₁ proxy.
     * @return frequency in [0,1], or {@code -1} when winner is absent / insufficient samples
     *         (caller must not overwrite generation confidence with 0.0).
     */
    public static double winningClusterFrequency(Collection<String> clusterHashes, String winnerHash) {
        if (clusterHashes == null || clusterHashes.isEmpty() || winnerHash == null) return -1;
        int n = 0;
        int win = 0;
        boolean seenWinner = false;
        for (String h : clusterHashes) {
            if (h == null || h.isBlank()) continue;
            n++;
            if (winnerHash.equals(h)) {
                win++;
                seenWinner = true;
            }
        }
        if (n < 2) return -1;
        if (!seenWinner) return -1;
        return clamp01((double) win / n);
    }

    public static double consistencyFromPrograms(List<Map<String, Object>> programs) {
        if (programs == null || programs.size() < 2) return 0.5;
        return consistency(programs.stream().map(CanonicalAstHasher::hashProgram).toList());
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
