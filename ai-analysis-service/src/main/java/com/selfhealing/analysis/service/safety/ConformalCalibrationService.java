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
 * Loads active conformal calibration (logistic + q̂ + Venn-Abers) and scores online.
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

    @Value("${mendr.conformal.va-max-width:0.25}")
    private double vaMaxWidth;

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
            ParsedWeights parsed = parseWeights(row);
            double q = ((Number) row.get("quantile_hat")).doubleValue();
            double alpha = row.get("risk_budget_alpha") == null
                    ? riskBudget
                    : ((Number) row.get("risk_budget_alpha")).doubleValue();
            Double mu = row.get("base_risk_mu") == null
                    ? null
                    : ((Number) row.get("base_risk_mu")).doubleValue();
            boolean crc = row.get("crc_feasible") == null
                    || Boolean.TRUE.equals(row.get("crc_feasible"));
            if (row.get("holdout_n") instanceof Number hn && hn.intValue() < 5) {
                crc = false;
            }
            String version = String.valueOf(row.get("model_version"));
            boolean vaFitted = parsed.vennAbers().size() > 0;
            active.set(new ActiveCalibration(
                    parsed.model(), parsed.vennAbers(), q, alpha, mu, crc, version, vaFitted));
            log.info("Loaded conformal calibration version={} q̂={} α={} vaN={}",
                    version, q, alpha, parsed.vennAbers().size());
        } catch (Exception e) {
            log.debug("conformal reload skipped (using bootstrap): {}", e.getMessage());
            active.set(ActiveCalibration.bootstrap(riskBudget));
        }
    }

    /**
     * CRC decision only — Venn-Abers width is gated separately in {@link SafetyGateService}.
     */
    public ConformalDecision decide(SafetyScore score) {
        ActiveCalibration cal = active.get();
        double s = Double.isFinite(score.nonconformityScore())
                ? score.nonconformityScore()
                : cal.model().predictFailureProbability(score.nonconformityFeatures());
        boolean withinBudget = s <= cal.quantileHat();
        boolean abstain = !withinBudget || !cal.crcFeasible();
        boolean autoEligible = withinBudget && cal.crcFeasible()
                && !score.wideInterval(vaMaxWidth);
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
        return score(modelConfidence, deterministicAgreement, metamorphicPassRate,
                specTrust, precedentQuality, 0.5, 0.5);
    }

    public SafetyScore score(
            double modelConfidence,
            double deterministicAgreement,
            double metamorphicPassRate,
            double specTrust,
            double precedentQuality,
            double semanticConsistency) {
        return score(modelConfidence, deterministicAgreement, metamorphicPassRate,
                specTrust, precedentQuality, semanticConsistency, 0.5);
    }

    public SafetyScore score(
            double modelConfidence,
            double deterministicAgreement,
            double metamorphicPassRate,
            double specTrust,
            double precedentQuality,
            double semanticConsistency,
            double causalVerification) {
        ActiveCalibration cal = active.get();
        SafetyScore partial = new SafetyScore(
                modelConfidence, deterministicAgreement, metamorphicPassRate,
                specTrust, precedentQuality, semanticConsistency, causalVerification,
                0.0, 0.5, 0.0, 1.0, 0.5, 1.0, false);
        double nc = cal.model().predictFailureProbability(partial.nonconformityFeatures());
        double rawCorrect = clamp01(1.0 - nc);
        boolean fitted = cal.vennAbersFitted() && cal.vennAbers().size() > 0;
        InductiveVennAbers.Multiprobability mp = cal.vennAbers().predict(rawCorrect);
        // When unfitted, expose raw as the point estimate for display; keep multiprobability
        // interval wide so width gate still forces HITL.
        double pVa = fitted ? mp.pVa() : rawCorrect;
        double p0 = fitted ? mp.p0() : 0.0;
        double p1 = fitted ? mp.p1() : 1.0;
        double width = fitted ? mp.width() : 1.0;
        return new SafetyScore(
                modelConfidence, deterministicAgreement, metamorphicPassRate,
                specTrust, precedentQuality, semanticConsistency, causalVerification, nc,
                rawCorrect, p0, p1, pVa, width, fitted);
    }

    public ActiveCalibration current() {
        return active.get();
    }

    public double riskBudget() {
        return riskBudget;
    }

    public double vaMaxWidth() {
        return vaMaxWidth;
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
    private ParsedWeights parseWeights(Map<String, Object> row) {
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
            LogisticNonconformityModel model =
                    new LogisticNonconformityModel(weights, bias, version, kind);
            InductiveVennAbers va = InductiveVennAbers.fromWeightsJson(weightsMap, version);
            return new ParsedWeights(model, va);
        } catch (Exception e) {
            log.warn("Failed to parse conformal weights: {}", e.getMessage());
            return new ParsedWeights(LogisticNonconformityModel.bootstrap(), InductiveVennAbers.bootstrap());
        }
    }

    /**
     * Fit logistic on train; fit VA on cal slice of holdout; ECE/AUROC/ablations on eval slice.
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
        List<LabeledExample> holdout = new ArrayList<>(shuffled.subList(split, shuffled.size()));

        double[] w = Arrays.copyOf(LogisticNonconformityModel.DEFAULT_WEIGHTS,
                LogisticNonconformityModel.FEATURE_DIM);
        double b = LogisticNonconformityModel.DEFAULT_BIAS;
        double lr = 0.15;
        for (int epoch = 0; epoch < 80; epoch++) {
            for (LabeledExample ex : train) {
                double pred = new LogisticNonconformityModel(w, b, "train")
                        .predictFailureProbability(ex.features());
                double err = pred - (ex.failed() ? 1.0 : 0.0);
                for (int i = 0; i < w.length && i < ex.features().length; i++) {
                    w[i] -= lr * err * ex.features()[i];
                }
                b -= lr * err;
            }
        }

        LogisticNonconformityModel model = new LogisticNonconformityModel(
                w, b, version, "logistic");

        // Split holdout: first half fits VA, second half evaluates ECE/AUROC (no in-sample ECE).
        int mid = Math.max(1, holdout.size() / 2);
        List<LabeledExample> vaCal = holdout.subList(0, mid);
        List<LabeledExample> eval = holdout.size() >= 2
                ? new ArrayList<>(holdout.subList(mid, holdout.size()))
                : List.of();
        // Never fall back to vaCal for ECE — that would be in-sample. Tiny holdouts
        // get evalN=0 diagnostics rather than a false held-out claim.

        List<Double> scores = new ArrayList<>();
        int failures = 0;
        List<InductiveVennAbers.ScoredLabel> vaExamples = new ArrayList<>();
        for (LabeledExample ex : vaCal) {
            double s = model.predictFailureProbability(ex.features());
            vaExamples.add(new InductiveVennAbers.ScoredLabel(
                    clamp01(1.0 - s), !ex.failed()));
        }
        for (LabeledExample ex : holdout) {
            double s = model.predictFailureProbability(ex.features());
            scores.add(s);
            if (ex.failed()) failures++;
        }
        Collections.sort(scores);
        double mu = holdout.isEmpty() ? 0.5 : (double) failures / holdout.size();
        boolean crcFeasible = mu <= alpha;
        if (holdout.size() < 5) {
            crcFeasible = false;
        }
        int idx = Math.min(scores.size() - 1,
                Math.max(0, (int) Math.ceil((1.0 - alpha) * (scores.size() + 1)) - 1));
        double qHat = scores.isEmpty() ? 0.5 : scores.get(idx);

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

        InductiveVennAbers va = InductiveVennAbers.fit(vaExamples, version + "-va");

        List<CalibrationDiagnostics.Scored> evalScored = new ArrayList<>();
        List<InductiveVennAbers.ScoredLabel> evalLabels = new ArrayList<>();
        for (LabeledExample ex : eval) {
            double s = model.predictFailureProbability(ex.features());
            double raw = clamp01(1.0 - s);
            double p = va.predict(raw).pVa();
            evalScored.add(new CalibrationDiagnostics.Scored(p, !ex.failed()));
            evalLabels.add(new InductiveVennAbers.ScoredLabel(raw, !ex.failed()));
        }
        boolean hasHeldOutEval = !eval.isEmpty();
        double ece = hasHeldOutEval ? CalibrationDiagnostics.ece(evalScored, 10) : Double.NaN;
        double auroc = hasHeldOutEval ? CalibrationDiagnostics.auroc(evalScored) : 0.5;
        Map<String, Double> ablations = hasHeldOutEval
                ? CalibrationDiagnostics.ablationAurocDeltas(model, eval)
                : Map.of();
        double vaVsRaw = hasHeldOutEval
                ? CalibrationDiagnostics.vennAbersVsRawAurocDelta(model, va, eval) : 0.0;
        if (hasHeldOutEval) {
            ablations.put("vennAbersVsRaw", vaVsRaw);
        }
        Map<String, Double> mpValidity = hasHeldOutEval
                ? CalibrationDiagnostics.multiprobabilityValidity(va, evalLabels)
                : Map.of();
        Map<String, Double> eceBaseline = hasHeldOutEval
                ? CalibrationDiagnostics.eceVsVerbalizedBaseline(model, va, eval, 10)
                : Map.of();
        Map<String, Double> selective = hasHeldOutEval
                ? CalibrationDiagnostics.selectivePredictionRates(model, va, eval, qHat, vaMaxWidth)
                : Map.of();
        Map<String, Double> ablationEce = hasHeldOutEval
                ? CalibrationDiagnostics.ablationEceDeltas(model, va, eval, 10)
                : Map.of();
        Map<String, Object> guard = CalibrationDiagnostics.calibrationGuard(
                ablations, ablationEce, eceBaseline, eval.size());
        if (hasHeldOutEval && auroc < 0.55 && eval.size() >= 20) {
            @SuppressWarnings("unchecked")
            List<String> fails = (List<String>) guard.computeIfAbsent("failures", k -> new ArrayList<>());
            fails.add("fullAUROC_below_0.55:" + auroc);
            guard.put("passed", false);
        }
        List<Map<String, Double>> reliability = hasHeldOutEval
                ? CalibrationDiagnostics.reliabilityDiagram(evalScored, 10)
                : List.of();

        Map<String, Object> weightsJson = new LinkedHashMap<>();
        weightsJson.put("weights", Arrays.stream(w).boxed().toList());
        weightsJson.put("bias", b);
        weightsJson.put("vennAbers", va.toWeightsFragment());
        weightsJson.put("ece", hasHeldOutEval ? ece : null);
        weightsJson.put("auroc", auroc);
        weightsJson.put("evalN", eval.size());
        weightsJson.put("heldOutEval", hasHeldOutEval);
        weightsJson.put("multiprobabilityValidity", mpValidity);
        // Scalar for backward compat — now empirical-in-interval, not majority heuristic.
        weightsJson.put("intervalCoverage", mpValidity.getOrDefault("empiricalInIntervalRate", 0.0));
        weightsJson.put("eceVsVerbalized", eceBaseline);
        weightsJson.put("selectivePrediction", selective);
        weightsJson.put("ablationAurocDelta", ablations);
        weightsJson.put("ablationEceDelta", ablationEce);
        weightsJson.put("calibrationGuard", guard);
        weightsJson.put("reliabilityDiagram", reliability);
        weightsJson.put("vennAbersFitted", va.size() > 0);
        weightsJson.put("vaMaxWidth", vaMaxWidth);
        // Plan Layer-1 index legend for operators reading weights_json.
        weightsJson.put("featureLegend", List.of(
                "s1_generationConfidence", "s2_deterministicAgreement", "s3_metamorphicPassRate",
                "specTrust", "s4_precedentQuality", "s6_semanticConsistency", "s5_causalVerification"));

        log.info("calibration diagnostics version={} heldOut={} evalN={} ece={} auroc={} guard={} selective={}",
                version, hasHeldOutEval, eval.size(), ece, auroc, guard.get("passed"), selective);

        return new FittedCalibration(model, va, qHat, alpha, mu, crcFeasible, empirical,
                holdout.size(), weightsJson, version,
                hasHeldOutEval ? ece : 1.0, auroc);
    }

    public record LabeledExample(double[] features, boolean failed) {}

    public record FittedCalibration(
            LogisticNonconformityModel model,
            InductiveVennAbers vennAbers,
            double quantileHat,
            double alpha,
            double baseRiskMu,
            boolean crcFeasible,
            double empiricalRisk,
            int holdoutN,
            Map<String, Object> weightsJson,
            String version,
            double ece,
            double auroc) {
        static FittedCalibration insufficient(double alpha, String version) {
            LogisticNonconformityModel m = LogisticNonconformityModel.bootstrap();
            InductiveVennAbers va = InductiveVennAbers.bootstrap();
            Map<String, Object> wj = new LinkedHashMap<>();
            wj.put("weights", Arrays.stream(m.weights()).boxed().toList());
            wj.put("bias", m.bias());
            wj.put("vennAbers", va.toWeightsFragment());
            wj.put("ece", 1.0);
            wj.put("auroc", 0.5);
            wj.put("vennAbersFitted", false);
            return new FittedCalibration(m, va, 0.55, alpha, 0.5, false, 0.5, 0, wj, version, 1.0, 0.5);
        }
    }

    public record ActiveCalibration(
            NonconformityModel model,
            InductiveVennAbers vennAbers,
            double quantileHat,
            double riskBudget,
            Double baseRiskMu,
            boolean crcFeasible,
            String version,
            boolean vennAbersFitted) {
        static ActiveCalibration bootstrap(double alpha) {
            return new ActiveCalibration(
                    LogisticNonconformityModel.bootstrap(),
                    InductiveVennAbers.bootstrap(),
                    0.35, alpha, null, false, "bootstrap-v0", false);
        }
    }

    private record ParsedWeights(LogisticNonconformityModel model, InductiveVennAbers vennAbers) {}

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
