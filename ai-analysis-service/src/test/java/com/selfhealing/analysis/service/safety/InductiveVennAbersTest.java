package com.selfhealing.analysis.service.safety;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InductiveVennAbersTest {

    @Test
    void bootstrapIsMaximallyWide() {
        InductiveVennAbers.Multiprobability mp = InductiveVennAbers.bootstrap().predict(0.9);
        assertThat(mp.width()).isEqualTo(1.0);
        assertThat(mp.pVa()).isEqualTo(0.5);
    }

    @Test
    void fittedProducesNarrowerIntervalThanBootstrap() {
        List<InductiveVennAbers.ScoredLabel> ex = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double s = i / 40.0;
            ex.add(new InductiveVennAbers.ScoredLabel(s, s > 0.45));
        }
        InductiveVennAbers va = InductiveVennAbers.fit(ex, "t");
        InductiveVennAbers.Multiprobability high = va.predict(0.9);
        InductiveVennAbers.Multiprobability low = va.predict(0.1);
        assertThat(high.pVa()).isGreaterThan(low.pVa());
        assertThat(high.width()).isLessThan(1.0);
    }

    @Test
    void pavaIsMonotone() {
        double[] y = {0.9, 0.1, 0.2, 0.8};
        double[] fitted = InductiveVennAbers.pava(y, y.length);
        for (int i = 1; i < fitted.length; i++) {
            assertThat(fitted[i]).isGreaterThanOrEqualTo(fitted[i - 1] - 1e-12);
        }
    }

    @Test
    void roundTripWeightsJson() {
        List<InductiveVennAbers.ScoredLabel> ex = List.of(
                new InductiveVennAbers.ScoredLabel(0.2, false),
                new InductiveVennAbers.ScoredLabel(0.8, true));
        InductiveVennAbers va = InductiveVennAbers.fit(ex, "v1");
        Map<String, Object> fragment = Map.of("vennAbers", va.toWeightsFragment());
        InductiveVennAbers restored = InductiveVennAbers.fromWeightsJson(fragment, "v1");
        assertThat(restored.size()).isEqualTo(2);
        assertThat(restored.predict(0.8).pVa()).isGreaterThan(0.4);
    }
}
