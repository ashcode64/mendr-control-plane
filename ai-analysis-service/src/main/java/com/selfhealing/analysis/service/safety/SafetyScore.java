package com.selfhealing.analysis.service.safety;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Layer-1 evidence + Layer-2 nonconformity + Layer-3 Venn-Abers calibrated probability.
 *
 * <p>Evidence slots (plan table): s₁ generationConfidence, s₂ deterministicAgreement,
 * s₃ metamorphicPassRate, s₄ precedentQuality (Wilson/Laplace), s₅ causalVerification,
 * s₆ semanticConsistency; plus contract {@code specTrust}. s₇ debate is not in the vector.
 *
 * <p>When {@code vennAbersFitted=false}, {@link #displayConfidence()} returns
 * {@code rawCorrectProbability} (logistic discrimination) rather than the uninformative
 * bootstrap midpoint 0.5. Wide {@code intervalWidth} still forces human review.
 */
public record SafetyScore(
        double modelConfidence,
        double deterministicAgreement,
        double metamorphicPassRate,
        double specTrust,
        double precedentQuality,
        double semanticConsistency,
        double causalVerification,
        double nonconformityScore,
        double rawCorrectProbability,
        double p0,
        double p1,
        double pVa,
        double intervalWidth,
        boolean vennAbersFitted) {

    /** Backward-compatible constructor used by older call sites / tests. */
    public SafetyScore(
            double modelConfidence,
            double deterministicAgreement,
            double metamorphicPassRate,
            double specTrust,
            double precedentQuality,
            double nonconformityScore) {
        this(modelConfidence, deterministicAgreement, metamorphicPassRate, specTrust,
                precedentQuality, 0.5, 0.5, nonconformityScore,
                clamp01(1.0 - nonconformityScore),
                0.0, 1.0, clamp01(1.0 - nonconformityScore), 1.0, false);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modelConfidence", modelConfidence);
        m.put("generationConfidence", modelConfidence);
        m.put("deterministicAgreement", deterministicAgreement);
        m.put("metamorphicPassRate", metamorphicPassRate);
        m.put("specTrust", specTrust);
        m.put("precedentQuality", precedentQuality);
        m.put("semanticConsistency", semanticConsistency);
        m.put("causalVerification", causalVerification);
        m.put("nonconformityScore", nonconformityScore);
        m.put("rawCorrectProbability", rawCorrectProbability);
        m.put("rawScore", rawCorrectProbability);
        m.put("p0", p0);
        m.put("p1", p1);
        m.put("pVa", pVa);
        m.put("intervalWidth", intervalWidth);
        m.put("vennAbersFitted", vennAbersFitted);
        m.put("calibratedConfidence", displayConfidence());
        return m;
    }

    /**
     * Feature vector (1−s): s₁ gen, s₂ det, s₃ meta, specTrust, s₄ prec, s₆ sem, s₅ causal.
     */
    public double[] nonconformityFeatures() {
        return new double[]{
                1.0 - clamp01(modelConfidence),
                1.0 - clamp01(deterministicAgreement),
                1.0 - clamp01(metamorphicPassRate),
                1.0 - clamp01(specTrust),
                1.0 - clamp01(precedentQuality),
                1.0 - clamp01(semanticConsistency),
                1.0 - clamp01(causalVerification)
        };
    }

    /**
     * Display / persisted confidence: calibrated {@code pVa} when VA is fitted;
     * otherwise logistic {@code rawCorrectProbability}.
     */
    public double displayConfidence() {
        if (!vennAbersFitted) {
            return clamp01(rawCorrectProbability);
        }
        return clamp01(pVa);
    }

    public boolean wideInterval(double maxWidth) {
        // Only enforce width gate once VA is informative; bootstrap width=1 always HITL
        // for auto-apply via crcFeasible=false, but we still treat unfitted VA as wide
        // for the explicit vennAbersWideInterval reason when width exceeds τ.
        return intervalWidth > Math.max(0.0, maxWidth);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
