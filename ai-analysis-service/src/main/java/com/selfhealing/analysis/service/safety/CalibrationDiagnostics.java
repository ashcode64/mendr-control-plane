package com.selfhealing.analysis.service.safety;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase E diagnostics: AUROC, ECE, VA-vs-raw / verbalized baselines, selective risk,
 * multiprobability validity, reliability diagrams, and ablation calibration guards.
 *
 * <p>Feature indices align with {@link LogisticNonconformityModel} / plan Layer-1:
 * <pre>
 *   0 s₁ generationConfidence (logprobs | cluster | verbalized)
 *   1 s₂ deterministicAgreement
 *   2 s₃ metamorphicPassRate
 *   3    specTrust (contract trust; not Wilson)
 *   4 s₄ precedentQuality (Wilson/Laplace)
 *   5 s₆ semanticConsistency
 *   6 s₅ causalVerification
 *   s₇ debate — flag+stub, not in the logistic vector
 * </pre>
 */
public final class CalibrationDiagnostics {

    private CalibrationDiagnostics() {}

    /**
     * Mann–Whitney AUROC. Scores sorted ascending (rank 1 = lowest);
     * higher score ⇒ more likely positive.
     */
    public static double auroc(List<Scored> scored) {
        if (scored == null || scored.size() < 2) return 0.5;
        List<Scored> sorted = new ArrayList<>(scored);
        sorted.sort(Comparator.comparingDouble(Scored::score));
        long pos = sorted.stream().filter(Scored::positive).count();
        long neg = sorted.size() - pos;
        if (pos == 0 || neg == 0) return 0.5;
        double rankSum = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).positive()) {
                rankSum += (i + 1);
            }
        }
        return (rankSum - pos * (pos + 1) / 2.0) / (pos * (double) neg);
    }

    public static double ece(List<Scored> scored, int bins) {
        if (scored == null || scored.isEmpty() || bins <= 0) return 1.0;
        double[] sumPred = new double[bins];
        double[] sumLabel = new double[bins];
        int[] count = new int[bins];
        for (Scored ex : scored) {
            double p = clamp01(ex.score());
            int b = Math.min(bins - 1, (int) Math.floor(p * bins));
            sumPred[b] += p;
            sumLabel[b] += ex.positive() ? 1.0 : 0.0;
            count[b]++;
        }
        double ece = 0.0;
        int n = scored.size();
        for (int b = 0; b < bins; b++) {
            if (count[b] == 0) continue;
            double conf = sumPred[b] / count[b];
            double acc = sumLabel[b] / count[b];
            ece += (count[b] / (double) n) * Math.abs(acc - conf);
        }
        return ece;
    }

    /**
     * Multiprobability validity proxies (Vovk–Petej / IVAP style), not p≥0.5 majority tests.
     * <ul>
     *   <li>{@code optimisticAccuracy} — mean of best-selector prob: y·p1 + (1−y)·(1−p0)</li>
     *   <li>{@code pessimisticAccuracy} — mean of worst-selector: y·p0 + (1−y)·(1−p1)</li>
     *   <li>{@code empiricalInIntervalRate} — binned empirical label rate ∈ [p̄0, p̄1]</li>
     * </ul>
     */
    public static Map<String, Double> multiprobabilityValidity(
            InductiveVennAbers va,
            List<InductiveVennAbers.ScoredLabel> examples) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (va == null || examples == null || examples.isEmpty()) {
            out.put("optimisticAccuracy", 0.0);
            out.put("pessimisticAccuracy", 0.0);
            out.put("empiricalInIntervalRate", 0.0);
            out.put("meanWidth", 1.0);
            return out;
        }
        double opt = 0, pess = 0, widthSum = 0;
        List<ValidityPoint> points = new ArrayList<>();
        for (InductiveVennAbers.ScoredLabel ex : examples) {
            InductiveVennAbers.Multiprobability mp = va.predict(ex.score());
            double p0 = mp.p0();
            double p1 = mp.p1();
            if (p1 < p0) {
                double t = p0;
                p0 = p1;
                p1 = t;
            }
            opt += ex.positive() ? p1 : (1.0 - p0);
            pess += ex.positive() ? p0 : (1.0 - p1);
            widthSum += Math.max(0.0, p1 - p0);
            points.add(new ValidityPoint(ex.score(), ex.positive() ? 1.0 : 0.0, p0, p1));
        }
        int n = examples.size();
        out.put("optimisticAccuracy", opt / n);
        out.put("pessimisticAccuracy", pess / n);
        out.put("empiricalInIntervalRate", empiricalInIntervalRate(points, 10));
        out.put("meanWidth", widthSum / n);
        return out;
    }

    /** @deprecated Use {@link #multiprobabilityValidity}; kept for callers expecting a scalar. */
    @Deprecated
    public static double intervalCoverage(
            InductiveVennAbers va,
            List<InductiveVennAbers.ScoredLabel> examples) {
        Map<String, Double> v = multiprobabilityValidity(va, examples);
        return v.getOrDefault("empiricalInIntervalRate", 0.0);
    }

    /**
     * Binned: sort by score, for each non-empty bin check whether empirical P(Y=1)
     * lies inside [mean p0, mean p1]. This is a finite-sample multiprobability check.
     */
    static double empiricalInIntervalRate(List<ValidityPoint> points, int bins) {
        if (points == null || points.isEmpty() || bins <= 0) return 0.0;
        List<ValidityPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(ValidityPoint::score));
        int n = sorted.size();
        int coveredBins = 0;
        int usedBins = 0;
        for (int b = 0; b < bins; b++) {
            int from = (b * n) / bins;
            int to = ((b + 1) * n) / bins;
            if (to <= from) continue;
            double sumY = 0, sumP0 = 0, sumP1 = 0;
            int c = to - from;
            for (int i = from; i < to; i++) {
                ValidityPoint p = sorted.get(i);
                sumY += p.y();
                sumP0 += p.p0();
                sumP1 += p.p1();
            }
            double emp = sumY / c;
            double lo = sumP0 / c;
            double hi = sumP1 / c;
            if (hi < lo) {
                double t = lo;
                lo = hi;
                hi = t;
            }
            usedBins++;
            if (emp + 1e-9 >= lo && emp - 1e-9 <= hi) {
                coveredBins++;
            }
        }
        return usedBins == 0 ? 0.0 : (double) coveredBins / usedBins;
    }

    public static Map<String, Double> ablationAurocDeltas(
            LogisticNonconformityModel model,
            List<ConformalCalibrationService.LabeledExample> examples) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        if (model == null || examples == null || examples.isEmpty()) return deltas;
        double baseline = auroc(scoreExamples(model, examples, -1));
        String[] names = {
                "s1_generationConfidence", "s2_deterministicAgreement", "s3_metamorphicPassRate",
                "specTrust", "s4_precedentQuality", "s6_semanticConsistency", "s5_causalVerification"
        };
        for (int i = 0; i < names.length && i < LogisticNonconformityModel.FEATURE_DIM; i++) {
            double ablated = auroc(scoreExamples(model, examples, i));
            deltas.put(names[i], baseline - ablated);
        }
        return deltas;
    }

    /**
     * ECE after ablating each feature (neutral 0.5), plus raw vs VA.
     * Positive delta ⇒ ablating that feature <b>worsens</b> calibration (feature helps).
     */
    public static Map<String, Double> ablationEceDeltas(
            LogisticNonconformityModel model,
            InductiveVennAbers va,
            List<ConformalCalibrationService.LabeledExample> examples,
            int bins) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        if (model == null || examples == null || examples.isEmpty()) return deltas;
        double baselineEce = ece(scoreVa(model, va, examples, -1), bins);
        String[] names = {
                "s1_generationConfidence", "s2_deterministicAgreement", "s3_metamorphicPassRate",
                "specTrust", "s4_precedentQuality", "s6_semanticConsistency", "s5_causalVerification"
        };
        for (int i = 0; i < names.length && i < LogisticNonconformityModel.FEATURE_DIM; i++) {
            double ablated = ece(scoreVa(model, va, examples, i), bins);
            deltas.put(names[i], ablated - baselineEce);
        }
        List<Scored> raw = new ArrayList<>();
        for (ConformalCalibrationService.LabeledExample ex : examples) {
            double nc = model.predictFailureProbability(padFeatures(ex.features()));
            raw.add(new Scored(clamp01(1.0 - nc), !ex.failed()));
        }
        double eceRaw = ece(raw, bins);
        // eceVa − eceRaw: negative ⇒ VA improved calibration.
        deltas.put("dropVA", baselineEce - eceRaw);
        deltas.put("eceFullVa", baselineEce);
        deltas.put("eceRawLogistic", eceRaw);
        return deltas;
    }

    /**
     * Eval guard: discrimination must not collapse; VA must not worsen ECE vs raw.
     */
    public static Map<String, Object> calibrationGuard(
            Map<String, Double> ablationAuroc,
            Map<String, Double> ablationEce,
            Map<String, Double> eceVsVerbalized,
            int evalN) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        double aurocCollapseTol = 0.10;
        double eceWorsenTol = 0.02;

        if (ablationAuroc != null) {
            for (String key : List.of("s2_deterministicAgreement", "s6_semanticConsistency")) {
                Double d = ablationAuroc.get(key);
                // delta = baseline − ablated; negative ⇒ ablating improved AUROC (feature hurts)
                // or feature was critical if largely positive. Collapse = large positive drop
                // when feature removed ⇒ ablated << baseline ⇒ delta large positive is OK.
                // Collapse of the *full* model is checked via vennAbersVsRaw separately.
                // Here: requiring that dropping s2/s6 does not *improve* AUROC by > tol
                // (i.e. those signals should not be actively harmful).
                if (d != null && d < -aurocCollapseTol) {
                    failures.add(key + "_AUROC_improved_when_ablated:" + d);
                }
            }
        }
        boolean vaHelpsOrNeutral = true;
        if (ablationEce != null && ablationEce.get("dropVA") != null) {
            // dropVA = eceVa − eceRaw; positive ⇒ VA worse than raw logistic.
            double dropVa = ablationEce.get("dropVA");
            vaHelpsOrNeutral = dropVa <= eceWorsenTol;
            out.put("vaEceDeltaVsRaw", dropVa);
            if (!vaHelpsOrNeutral) {
                failures.add("vaWorsensEce:" + dropVa);
            }
        }
        if (eceVsVerbalized != null && eceVsVerbalized.get("eceImprovement") != null) {
            double imp = eceVsVerbalized.get("eceImprovement");
            out.put("eceImprovementVsVerbalized", imp);
            // eceImprovement = eceVerb − eceVa; negative ⇒ VA worse than verbalized.
            if (imp < -eceWorsenTol) {
                failures.add("vaWorsensEceVsVerbalized:" + imp);
            }
        }
        out.put("evalN", (double) evalN);
        out.put("vaDoesNotWorsenEce", vaHelpsOrNeutral);
        out.put("passed", failures.isEmpty());
        out.put("failures", failures);
        return out;
    }

    /** Binned reliability diagram: mean predicted vs empirical positive rate per bin. */
    public static List<Map<String, Double>> reliabilityDiagram(List<Scored> scored, int bins) {
        List<Map<String, Double>> out = new ArrayList<>();
        if (scored == null || scored.isEmpty() || bins <= 0) return out;
        double[] sumPred = new double[bins];
        double[] sumLabel = new double[bins];
        int[] count = new int[bins];
        for (Scored ex : scored) {
            double p = clamp01(ex.score());
            int b = Math.min(bins - 1, (int) Math.floor(p * bins));
            sumPred[b] += p;
            sumLabel[b] += ex.positive() ? 1.0 : 0.0;
            count[b]++;
        }
        for (int b = 0; b < bins; b++) {
            if (count[b] == 0) continue;
            Map<String, Double> row = new LinkedHashMap<>();
            row.put("bin", (double) b);
            row.put("binLo", b / (double) bins);
            row.put("binHi", (b + 1) / (double) bins);
            row.put("count", (double) count[b]);
            row.put("meanPredicted", sumPred[b] / count[b]);
            row.put("empiricalPositiveRate", sumLabel[b] / count[b]);
            out.add(row);
        }
        return out;
    }

    private static List<Scored> scoreVa(
            LogisticNonconformityModel model,
            InductiveVennAbers va,
            List<ConformalCalibrationService.LabeledExample> examples,
            int ablateIndex) {
        List<Scored> out = new ArrayList<>();
        for (ConformalCalibrationService.LabeledExample ex : examples) {
            double[] f = padFeatures(ex.features());
            if (ablateIndex >= 0 && ablateIndex < f.length) {
                f[ablateIndex] = 0.5;
            }
            double nc = model.predictFailureProbability(f);
            double raw = clamp01(1.0 - nc);
            double p = va == null || va.size() == 0 ? raw : va.predict(raw).pVa();
            out.add(new Scored(p, !ex.failed()));
        }
        return out;
    }

    public static double vennAbersVsRawAurocDelta(
            LogisticNonconformityModel model,
            InductiveVennAbers va,
            List<ConformalCalibrationService.LabeledExample> examples) {
        if (model == null || va == null || examples == null || examples.isEmpty()) return 0.0;
        List<Scored> raw = new ArrayList<>();
        List<Scored> calibrated = new ArrayList<>();
        for (ConformalCalibrationService.LabeledExample ex : examples) {
            double nc = model.predictFailureProbability(padFeatures(ex.features()));
            double r = clamp01(1.0 - nc);
            boolean pos = !ex.failed();
            raw.add(new Scored(r, pos));
            calibrated.add(new Scored(va.predict(r).pVa(), pos));
        }
        return auroc(calibrated) - auroc(raw);
    }

    /**
     * ECE of Venn-Abers pVa vs ECE of verbalized/generation confidence (feature index 0).
     * Positive {@code eceImprovement} ⇒ VA is better calibrated than verbalized baseline.
     */
    public static Map<String, Double> eceVsVerbalizedBaseline(
            LogisticNonconformityModel model,
            InductiveVennAbers va,
            List<ConformalCalibrationService.LabeledExample> examples,
            int bins) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (model == null || va == null || examples == null || examples.isEmpty()) {
            out.put("eceVa", 1.0);
            out.put("eceVerbalized", 1.0);
            out.put("eceImprovement", 0.0);
            return out;
        }
        List<Scored> vaScores = new ArrayList<>();
        List<Scored> verbal = new ArrayList<>();
        for (ConformalCalibrationService.LabeledExample ex : examples) {
            double[] f = padFeatures(ex.features());
            double nc = model.predictFailureProbability(f);
            double raw = clamp01(1.0 - nc);
            boolean pos = !ex.failed();
            vaScores.add(new Scored(va.predict(raw).pVa(), pos));
            // features[0] = 1 − generationConfidence
            verbal.add(new Scored(clamp01(1.0 - f[0]), pos));
        }
        double eceVa = ece(vaScores, bins);
        double eceVerb = ece(verbal, bins);
        out.put("eceVa", eceVa);
        out.put("eceVerbalized", eceVerb);
        out.put("eceImprovement", eceVerb - eceVa);
        return out;
    }

    /**
     * Selective prediction under width τ and conformal quantile q̂.
     * {@code humanReviewRate} = fraction with width &gt; τ;
     * {@code wrongAutoApplyRate} = empirical wrong among non-wide &amp; s≤q̂.
     */
    public static Map<String, Double> selectivePredictionRates(
            LogisticNonconformityModel model,
            InductiveVennAbers va,
            List<ConformalCalibrationService.LabeledExample> examples,
            double quantileHat,
            double vaMaxWidth) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (model == null || va == null || examples == null || examples.isEmpty()) {
            out.put("humanReviewRate", 1.0);
            out.put("wrongAutoApplyRate", 0.0);
            out.put("autoEligibleFraction", 0.0);
            return out;
        }
        int wide = 0;
        int autoElig = 0;
        int wrongAuto = 0;
        for (ConformalCalibrationService.LabeledExample ex : examples) {
            double nc = model.predictFailureProbability(padFeatures(ex.features()));
            double raw = clamp01(1.0 - nc);
            InductiveVennAbers.Multiprobability mp = va.predict(raw);
            if (mp.width() > vaMaxWidth) {
                wide++;
                continue;
            }
            if (nc <= quantileHat) {
                autoElig++;
                if (ex.failed()) wrongAuto++;
            }
        }
        int n = examples.size();
        out.put("humanReviewRate", (double) wide / n);
        out.put("autoEligibleFraction", (double) autoElig / n);
        out.put("wrongAutoApplyRate", autoElig == 0 ? 0.0 : (double) wrongAuto / autoElig);
        return out;
    }

    private static List<Scored> scoreExamples(
            LogisticNonconformityModel model,
            List<ConformalCalibrationService.LabeledExample> examples,
            int ablateIndex) {
        List<Scored> out = new ArrayList<>();
        for (ConformalCalibrationService.LabeledExample ex : examples) {
            double[] f = padFeatures(ex.features());
            if (ablateIndex >= 0 && ablateIndex < f.length) {
                f[ablateIndex] = 0.5;
            }
            double nc = model.predictFailureProbability(f);
            out.add(new Scored(1.0 - nc, !ex.failed()));
        }
        return out;
    }

    private static double[] padFeatures(double[] features) {
        double[] f = new double[LogisticNonconformityModel.FEATURE_DIM];
        Arrays.fill(f, 0.5);
        if (features != null) {
            System.arraycopy(features, 0, f, 0, Math.min(features.length, f.length));
        }
        return f;
    }

    public record Scored(double score, boolean positive) {}

    record ValidityPoint(double score, double y, double p0, double p1) {}

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
