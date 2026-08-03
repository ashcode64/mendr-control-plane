package com.selfhealing.analysis.service.safety;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inductive Venn-Abers Predictor (IVAP): finite-sample multiprobability
 * calibration via two isotonic regressions (Vovk &amp; Petej; Nouretdinov et al.).
 *
 * <p>For score {@code s}, fit isotonic on calibration∪{(s,0)} → {@code p0} and
 * calibration∪{(s,1)} → {@code p1}. Point estimate
 * {@code pVa = p1 / (1 - p0 + p1)}; width {@code p1 - p0} is epistemic uncertainty.
 */
public final class InductiveVennAbers {

    private final double[] calScores;
    private final int[] calLabels;
    private final String version;

    private InductiveVennAbers(double[] calScores, int[] calLabels, String version) {
        this.calScores = calScores;
        this.calLabels = calLabels;
        this.version = version == null ? "bootstrap-va" : version;
    }

    public record Multiprobability(double p0, double p1, double pVa, double width) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("p0", p0);
            m.put("p1", p1);
            m.put("pVa", pVa);
            m.put("width", width);
            return m;
        }
    }

    /** Wide uninformative interval until enough labeled outcomes exist. */
    public static InductiveVennAbers bootstrap() {
        return new InductiveVennAbers(new double[0], new int[0], "bootstrap-va");
    }

    public static InductiveVennAbers fit(List<ScoredLabel> examples, String version) {
        if (examples == null || examples.isEmpty()) {
            return bootstrap();
        }
        List<ScoredLabel> sorted = new ArrayList<>(examples);
        sorted.sort(Comparator.comparingDouble(ScoredLabel::score));
        double[] scores = new double[sorted.size()];
        int[] labels = new int[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            scores[i] = sorted.get(i).score();
            labels[i] = sorted.get(i).positive() ? 1 : 0;
        }
        return new InductiveVennAbers(scores, labels, version);
    }

    @SuppressWarnings("unchecked")
    public static InductiveVennAbers fromWeightsJson(Map<String, Object> weightsMap, String version) {
        if (weightsMap == null) return bootstrap();
        Object va = weightsMap.get("vennAbers");
        if (!(va instanceof Map<?, ?> vaMap)) return bootstrap();
        Object sObj = vaMap.get("scores");
        Object lObj = vaMap.get("labels");
        if (!(sObj instanceof List<?> sList) || !(lObj instanceof List<?> lList)) {
            return bootstrap();
        }
        if (sList.size() != lList.size() || sList.isEmpty()) return bootstrap();
        double[] scores = new double[sList.size()];
        int[] labels = new int[lList.size()];
        for (int i = 0; i < sList.size(); i++) {
            scores[i] = ((Number) sList.get(i)).doubleValue();
            labels[i] = ((Number) lList.get(i)).intValue();
        }
        String v = vaMap.get("version") instanceof String vs ? vs : version;
        return new InductiveVennAbers(scores, labels, v);
    }

    public Map<String, Object> toWeightsFragment() {
        Map<String, Object> va = new LinkedHashMap<>();
        va.put("scores", Arrays.stream(calScores).boxed().toList());
        va.put("labels", Arrays.stream(calLabels).boxed().toList());
        va.put("version", version);
        return va;
    }

    public Multiprobability predict(double rawCorrectProbability) {
        double s = clamp01(rawCorrectProbability);
        if (calScores.length == 0) {
            // Uninformative: force wide epistemic interval → human review.
            return new Multiprobability(0.0, 1.0, 0.5, 1.0);
        }
        double p0 = isotonicAt(s, 0);
        double p1 = isotonicAt(s, 1);
        if (p1 < p0) {
            // Numerical guard — swap to keep a valid interval.
            double tmp = p0;
            p0 = p1;
            p1 = tmp;
        }
        double denom = 1.0 - p0 + p1;
        double pVa = denom <= 1e-12 ? 0.5 : p1 / denom;
        return new Multiprobability(p0, p1, clamp01(pVa), Math.max(0.0, p1 - p0));
    }

    public String version() {
        return version;
    }

    public int size() {
        return calScores.length;
    }

    public record ScoredLabel(double score, boolean positive) {}

    private double isotonicAt(double testScore, int hypotheticalLabel) {
        int n = calScores.length + 1;
        double[] xs = new double[n];
        double[] ys = new double[n];
        int k = 0;
        boolean inserted = false;
        for (int i = 0; i < calScores.length; i++) {
            if (!inserted && testScore <= calScores[i]) {
                xs[k] = testScore;
                ys[k] = hypotheticalLabel;
                k++;
                inserted = true;
            }
            xs[k] = calScores[i];
            ys[k] = calLabels[i];
            k++;
        }
        if (!inserted) {
            xs[k] = testScore;
            ys[k] = hypotheticalLabel;
            k++;
        }
        double[] fitted = pava(ys, k);
        // Find fitted value at testScore (first matching x).
        for (int i = 0; i < k; i++) {
            if (Math.abs(xs[i] - testScore) < 1e-15) {
                return clamp01(fitted[i]);
            }
        }
        // Nearest score
        int best = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < k; i++) {
            double d = Math.abs(xs[i] - testScore);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return clamp01(fitted[best]);
    }

    /** Pool Adjacent Violators Algorithm — non-decreasing isotonic regression. */
    static double[] pava(double[] y, int n) {
        double[] level = new double[n];
        int[] weight = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            level[m] = y[i];
            weight[m] = 1;
            m++;
            while (m >= 2 && level[m - 2] > level[m - 1]) {
                double w0 = weight[m - 2];
                double w1 = weight[m - 1];
                level[m - 2] = (w0 * level[m - 2] + w1 * level[m - 1]) / (w0 + w1);
                weight[m - 2] = weight[m - 2] + weight[m - 1];
                m--;
            }
        }
        double[] out = new double[n];
        int idx = 0;
        for (int b = 0; b < m; b++) {
            for (int j = 0; j < weight[b]; j++) {
                out[idx++] = level[b];
            }
        }
        return out;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
