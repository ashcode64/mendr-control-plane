package com.selfhealing.analysis.service.safety;

import java.util.Arrays;

/**
 * Logistic regressor for nonconformity: σ(w·x + b).
 *
 * <p>Feature order (length 7) — plan Layer-1 mapping:
 * <pre>
 *   0  1−s₁ generationConfidence (logprobs | cluster | verbalized)
 *   1  1−s₂ deterministicAgreement
 *   2  1−s₃ metamorphicPassRate
 *   3  1−specTrust (contract)
 *   4  1−s₄ precedentQuality (Wilson/Laplace)
 *   5  1−s₆ semanticConsistency
 *   6  1−s₅ causalVerification
 * </pre>
 * s₇ debate is flag-gated and not part of this vector.
 */
public final class LogisticNonconformityModel implements NonconformityModel {

    public static final int FEATURE_DIM = 7;

    public static final double[] DEFAULT_WEIGHTS = {1.2, 0.9, 1.1, 0.8, 0.7, 0.6, 1.0};
    public static final double DEFAULT_BIAS = -1.5;

    private final double[] weights;
    private final double bias;
    private final String version;
    private final String kind;

    public LogisticNonconformityModel(double[] weights, double bias, String version) {
        this(weights, bias, version, "logistic");
    }

    public LogisticNonconformityModel(double[] weights, double bias, String version, String kind) {
        this.weights = normalizeWeights(weights);
        this.bias = Double.isNaN(bias) ? DEFAULT_BIAS : bias;
        this.version = version == null || version.isBlank() ? "bootstrap-v0" : version;
        this.kind = kind == null ? "logistic" : kind;
    }

    public static LogisticNonconformityModel bootstrap() {
        return new LogisticNonconformityModel(DEFAULT_WEIGHTS, DEFAULT_BIAS, "bootstrap-v0");
    }

    static double[] normalizeWeights(double[] weights) {
        double[] out = Arrays.copyOf(DEFAULT_WEIGHTS, FEATURE_DIM);
        if (weights == null || weights.length == 0) return out;
        int n = Math.min(weights.length, FEATURE_DIM);
        System.arraycopy(weights, 0, out, 0, n);
        return out;
    }

    @Override
    public double predictFailureProbability(double[] features) {
        if (features == null || features.length == 0) {
            return sigmoid(bias);
        }
        double z = bias;
        int n = Math.min(features.length, weights.length);
        for (int i = 0; i < n; i++) {
            z += weights[i] * features[i];
        }
        return sigmoid(z);
    }

    @Override
    public String modelKind() {
        return kind;
    }

    @Override
    public String modelVersion() {
        return version;
    }

    public double[] weights() {
        return Arrays.copyOf(weights, weights.length);
    }

    public double bias() {
        return bias;
    }

    private static double sigmoid(double z) {
        if (z >= 20) return 1.0;
        if (z <= -20) return 0.0;
        return 1.0 / (1.0 + Math.exp(-z));
    }
}
