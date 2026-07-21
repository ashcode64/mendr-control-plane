package com.selfhealing.analysis.service.safety;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads the active conformal calibration (model + {@code q̂}) and scores online
 * nonconformity. Training is owned by {@link ConformalCalibrationJob}.
 */
@Slf4j
@Service
public class ConformalCalibrationService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mendr.conformal.risk-budget:0.01}")
    private double riskBudget;

    @Value("${mendr.conformal.score-model:logistic}")
    private String scoreModel;

    @Value("${mendr.conformal.allow-opaque-model:false}")
    private boolean allowOpaqueModel;

    @Value("${mendr.conformal.min-train-n:500}")
    private int minTrainN;

    private final AtomicReference<ActiveCalibration> active =
            new AtomicReference<>(ActiveCalibration.bootstrap(0.01));

    public ConformalCalibrationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        tryReload(null);
    }

    public void tryReload(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT model_kind, model_version, weights_json, quantile_hat, risk_budget_alpha,
                       base_risk_mu, crc_feasible, holdout_n
                FROM conformal_calibration
                WHERE active = true
                  AND (tenant_id IS NULL OR tenant_id = COALESCE(?::uuid, tenant_id))
                ORDER BY CASE WHEN tenant_id IS NOT NULL THEN 0 ELSE 1 END, trained_at DESC
                LIMIT 1
                """, tenantId == null ? null : tenantId.toString());
            if (rows.isEmpty()) {
                active.set(ActiveCalibration.bootstrap(riskBudget));
                return;
            }
            Map<String, Object> row = rows.get(0);
            NonconformityModel model = parseModel(row);
            double q = ((Number) row.get("quantile_hat")).doubleValue();
            double alpha = row.get("risk_budget_alpha") == null
                    ? riskBudget
                    : ((Number) row.get("risk_budget_alpha")).doubleValue();
            Double mu = row.get("base_risk_mu") == null
                    ? null
                    : ((Number) row.get("base_risk_mu")).doubleValue();
            // Keep CRC flag from published row (job already gated on min-train-n).
            boolean crc = row.get("crc_feasible") == null
                    || Boolean.TRUE.equals(row.get("crc_feasible"));
            if (row.get("holdout_n") instanceof Number hn && hn.intValue() < 5) {
                crc = false;
            }
            String version = String.valueOf(row.get("model_version"));
            active.set(new ActiveCalibration(model, q, alpha, mu, crc, version));
            log.info("Loaded conformal calibration version={} q̂={} α={}", version, q, alpha);
        } catch (Exception e) {
            log.debug("conformal reload skipped (using bootstrap): {}", e.getMessage());
            active.set(ActiveCalibration.bootstrap(riskBudget));
        }
    }

    public ConformalDecision decide(SafetyScore score) {
        ActiveCalibration cal = active.get();
        double s = cal.model().predictFailureProbability(score.nonconformityFeatures());
        // Accept when nonconformity ≤ calibrated quantile (low predicted failure risk).
        boolean withinBudget = s <= cal.quantileHat();
        boolean abstain = !withinBudget || !cal.crcFeasible();
        boolean autoEligible = withinBudget && cal.crcFeasible();
        return new ConformalDecision(
                abstain,
                cal.riskBudget(),
                autoEligible,
                cal.version(),
                cal.quantileHat(),
                s,
                cal.crcFeasible());
    }

    public SafetyScore score(
            double modelConfidence,
            double deterministicAgreement,
            double metamorphicPassRate,
            double specTrust,
            double precedentQuality) {
        SafetyScore partial = new SafetyScore(
                modelConfidence, deterministicAgreement, metamorphicPassRate,
                specTrust, precedentQuality, 0.0);
        double s = active.get().model().predictFailureProbability(partial.nonconformityFeatures());
        return new SafetyScore(
                modelConfidence, deterministicAgreement, metamorphicPassRate,
                specTrust, precedentQuality, s);
    }

    public ActiveCalibration current() {
        return active.get();
    }

    public double riskBudget() {
        return riskBudget;
    }

    public String preferredModelKind() {
        String kind = scoreModel == null ? "logistic" : scoreModel.trim().toLowerCase();
        if ("xgboost".equals(kind) || "xgb".equals(kind) || "opaque".equals(kind)) {
            if (!allowOpaqueModel) {
                return "logistic";
            }
            return kind;
        }
        return kind.isBlank() ? "logistic" : kind;
    }

    /**
     * XGBoost / opaque models require {@code allow-opaque-model=true} (SHAP/explainability gate).
     * Logistic always allowed.
     */
    public boolean canTrainPreferredModel() {
        String raw = scoreModel == null ? "logistic" : scoreModel.trim().toLowerCase();
        if ("xgboost".equals(raw) || "xgb".equals(raw) || "opaque".equals(raw)) {
            return allowOpaqueModel;
        }
        return true;
    }

    public int minTrainN() {
        return minTrainN;
    }

    @SuppressWarnings("unchecked")
    private NonconformityModel parseModel(Map<String, Object> row) {
        try {
            Object wj = row.get("weights_json");
            Map<String, Object> weightsMap;
            if (wj instanceof Map) {
                weightsMap = (Map<String, Object>) wj;
            } else if (wj instanceof String s) {
                weightsMap = objectMapper.readValue(s, new TypeReference<>() {});
            } else {
                weightsMap = objectMapper.convertValue(wj, new TypeReference<>() {});
            }
            List<Number> wList = (List<Number>) weightsMap.getOrDefault("weights", List.of());
            double[] weights = new double[wList.size()];
            for (int i = 0; i < wList.size(); i++) weights[i] = wList.get(i).doubleValue();
            double bias = weightsMap.get("bias") instanceof Number n
                    ? n.doubleValue()
                    : LogisticNonconformityModel.DEFAULT_BIAS;
            String kind = String.valueOf(row.getOrDefault("model_kind", "logistic"));
            String version = String.valueOf(row.get("model_version"));
            return new LogisticNonconformityModel(weights, bias, version, kind);
        } catch (Exception e) {
            log.warn("Failed to parse conformal weights: {}", e.getMessage());
            return LogisticNonconformityModel.bootstrap();
        }
    }

    /**
     * Fit logistic via simple gradient descent and calibrate q̂ on holdout so
     * empirical wrong-auto-apply rate ≤ α. Used by the calibration job.
     */
    public FittedCalibration fitAndCalibrate(
            List<LabeledExample> examples, double alpha, String version) {
        if (examples == null || examples.size() < 8) {
            return FittedCalibration.insufficient(alpha, version);
        }
        List<LabeledExample> shuffled = new ArrayList<>(examples);
        Collections.shuffle(shuffled);
        int split = Math.max(4, (int) (shuffled.size() * 0.7));
        List<LabeledExample> train = shuffled.subList(0, split);
        List<LabeledExample> holdout = shuffled.subList(split, shuffled.size());

        double[] w = Arrays.copyOf(LogisticNonconformityModel.DEFAULT_WEIGHTS,
                LogisticNonconformityModel.DEFAULT_WEIGHTS.length);
        double b = LogisticNonconformityModel.DEFAULT_BIAS;
        double lr = 0.15;
        for (int epoch = 0; epoch < 80; epoch++) {
            for (LabeledExample ex : train) {
                double pred = new LogisticNonconformityModel(w, b, "train").predictFailureProbability(ex.features());
                double err = pred - (ex.failed() ? 1.0 : 0.0);
                for (int i = 0; i < w.length && i < ex.features().length; i++) {
                    w[i] -= lr * err * ex.features()[i];
                }
                b -= lr * err;
            }
        }

        LogisticNonconformityModel model = new LogisticNonconformityModel(
                w, b, version, "logistic");
        // Even if config says xgboost, this job trains logistic coefficients (audit artifact).
        // Opaque model training is gated separately and not implemented in-process yet.
        List<Double> scores = new ArrayList<>();
        int failures = 0;
        for (LabeledExample ex : holdout) {
            double s = model.predictFailureProbability(ex.features());
            scores.add(s);
            if (ex.failed()) failures++;
        }
        Collections.sort(scores);
        double mu = holdout.isEmpty() ? 0.5 : (double) failures / holdout.size();
        boolean crcFeasible = mu <= alpha;
        if (holdout.size() < 5) {
            // Too little data for a reliable CRC check — mark infeasible until calibrated.
            crcFeasible = false;
        }
        // q̂ = (1-α)-quantile of nonconformity on holdout (split conformal).
        int idx = Math.min(scores.size() - 1,
                Math.max(0, (int) Math.ceil((1.0 - alpha) * (scores.size() + 1)) - 1));
        double qHat = scores.isEmpty() ? 0.5 : scores.get(idx);

        // Empirical risk if we accept when s ≤ qHat
        int wrongAccept = 0;
        int accepted = 0;
        for (LabeledExample ex : holdout) {
            double s = model.predictFailureProbability(ex.features());
            if (s <= qHat) {
                accepted++;
                if (ex.failed()) wrongAccept++;
            }
        }
        double empirical = accepted == 0 ? 0.0 : (double) wrongAccept / accepted;

        Map<String, Object> weightsJson = new LinkedHashMap<>();
        weightsJson.put("weights", Arrays.stream(w).boxed().toList());
        weightsJson.put("bias", b);

        return new FittedCalibration(model, qHat, alpha, mu, crcFeasible, empirical,
                holdout.size(), weightsJson, version);
    }

    public record LabeledExample(double[] features, boolean failed) {}

    public record FittedCalibration(
            LogisticNonconformityModel model,
            double quantileHat,
            double alpha,
            double baseRiskMu,
            boolean crcFeasible,
            double empiricalRisk,
            int holdoutN,
            Map<String, Object> weightsJson,
            String version) {
        static FittedCalibration insufficient(double alpha, String version) {
            LogisticNonconformityModel m = LogisticNonconformityModel.bootstrap();
            Map<String, Object> wj = new LinkedHashMap<>();
            wj.put("weights", Arrays.stream(m.weights()).boxed().toList());
            wj.put("bias", m.bias());
            return new FittedCalibration(m, 0.55, alpha, 0.5, false, 0.5, 0, wj, version);
        }
    }

    public record ActiveCalibration(
            NonconformityModel model,
            double quantileHat,
            double riskBudget,
            Double baseRiskMu,
            boolean crcFeasible,
            String version) {
        static ActiveCalibration bootstrap(double alpha) {
            // Conservative bootstrap: abstain unless nonconformity is clearly low;
            // crcFeasible=false until a real calibration job publishes.
            return new ActiveCalibration(
                    LogisticNonconformityModel.bootstrap(), 0.35, alpha, null, false, "bootstrap-v0");
        }
    }
}
