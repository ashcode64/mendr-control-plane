package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WilsonIntervalTest {

    @Test
    void perfectSuccessesHaveHighLowerBound() {
        var w = PrecedentsQualitySweeper.wilson(10, 10, 1.96);
        assertThat(w.lower()).isGreaterThan(0.7);
        assertThat(w.upper()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void allFailuresHaveLowUpperBound() {
        var w = PrecedentsQualitySweeper.wilson(0, 10, 1.96);
        assertThat(w.upper()).isLessThan(0.4);
    }
}
