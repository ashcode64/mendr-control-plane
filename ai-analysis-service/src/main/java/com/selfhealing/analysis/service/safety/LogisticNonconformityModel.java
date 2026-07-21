package com.selfhealing.analysis.service.safety;

import java.util.Arrays;

/**
 * Logistic regressor for nonconformity: σ(w·x + b). Default weights are a
 * conservative cold-start bootstrap until {@link ConformalCalibrationJob} retrains
 * from ≥ {@code mendr.conformal.min-train-n} labeled outcomes.
 * Opaque / XGBoost scoring is gated behind {@code mendr.conformal.allow-opaque-model}
 * and requires a SHAP/explanation export path — this class remains the audit default.
 */
public final class LogisticNonconformityModel implements NonconformityModel {

    /** Features: 1-conf, 1-detAgree, 1-meta, 1-specTrust */
    public static final double[] DEFAULT_WEIGHTS = {1.2, 0.9, 1.1, 0.8};
    public static final double DEFAULT_BIAS = -1.5;

    private final double[] weights;
    private final double bias;
    private final String version;
    private final String kind;

    public LogisticNonconformityModel(double[] weights, double bias, String version) {
        this(weights, bias, version, "logistic");
    }

    public LogisticNonconformityModel(double[] weights, double bias, String version, String kind) {
        this.weights = weights == null || weights.length == 0
                ? Arrays.copyOf(DEFAULT_WEIGHTS, DEFAULT_WEIGHTS.length)
                : Arrays.copyOf(weights, weights.length);
        this.bias = Double.isNaN(bias) ? DEFAULT_BIAS : bias;
        this.version = version == null || version.isBlank() ? "bootstrap-v0" : version;
        this.kind = kind == null ? "logistic" : kind;
    }

    public static LogisticNonconformityModel bootstrap() {
        return new LogisticNonconformityModel(DEFAULT_WEIGHTS, DEFAULT_BIAS, "bootstrap-v0");
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
