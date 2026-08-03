package com.selfhealing.analysis.service.safety;

/**
 * Wilson score interval for a binomial proportion (Wilson 1927).
 * Shared by post-deploy quality lifecycle and pre-apply precedent signal s₄.
 */
public final class WilsonScore {

    private WilsonScore() {}

    public record Interval(double lower, double upper) {}

    public static Interval interval(int successes, int n, double z) {
        if (n <= 0) return new Interval(0, 1);
        double phat = (double) successes / n;
        double z2 = z * z;
        double denom = 1.0 + z2 / n;
        double centre = phat + z2 / (2.0 * n);
        double margin = z * Math.sqrt((phat * (1.0 - phat) + z2 / (4.0 * n)) / n);
        double lower = (centre - margin) / denom;
        double upper = (centre + margin) / denom;
        return new Interval(Math.max(0, lower), Math.min(1, upper));
    }

    /**
     * Precedent quality: Wilson lower bound once {@code n >= minN}, else Laplace
     * {@code (successes+1)/(n+2)}.
     */
    public static double quality(int successes, int failures, int minN, double z) {
        int n = successes + failures;
        if (n == 0) return 0.5;
        if (n < Math.max(1, minN)) {
            return clamp01((successes + 1.0) / (n + 2.0));
        }
        return clamp01(interval(successes, n, z).lower());
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
