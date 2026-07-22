package com.selfhealing.analysis.service.safety;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inputs to the Phase 8 Safety Gate. {@code nonconformityScore} is predicted
 * P(failure) from the learned model (logistic / XGBoost); higher = less safe.
 */
public record SafetyScore(
        double modelConfidence,
        double deterministicAgreement,
        double metamorphicPassRate,
        double specTrust,
        double precedentQuality,
        double nonconformityScore) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modelConfidence", modelConfidence);
        m.put("deterministicAgreement", deterministicAgreement);
        m.put("metamorphicPassRate", metamorphicPassRate);
        m.put("specTrust", specTrust);
        m.put("precedentQuality", precedentQuality);
        m.put("nonconformityScore", nonconformityScore);
        return m;
    }

    /** Feature vector for the nonconformity model: 1 − each quality signal. */
    public double[] nonconformityFeatures() {
        return new double[]{
                1.0 - clamp01(modelConfidence),
                1.0 - clamp01(deterministicAgreement),
                1.0 - clamp01(metamorphicPassRate),
                1.0 - clamp01(specTrust),
                1.0 - clamp01(precedentQuality)
        };
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
