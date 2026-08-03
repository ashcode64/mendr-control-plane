package com.selfhealing.analysis.service.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrecedentQualityScorerMathTest {

    @Test
    void nonconformityFeaturesIncludePrecedentAndCausal() {
        SafetyScore score = new SafetyScore(1.0, 1.0, 1.0, 1.0, 0.0, 0.5);
        double[] f = score.nonconformityFeatures();
        assertEquals(7, f.length);
        assertEquals(1.0, f[4], 1e-9); // 1 - 0.0 precedentQuality
        assertEquals(0.5, f[5], 1e-9); // 1 - 0.5 semantic
        assertEquals(0.5, f[6], 1e-9); // 1 - 0.5 causal (compat ctor default)
    }
}
