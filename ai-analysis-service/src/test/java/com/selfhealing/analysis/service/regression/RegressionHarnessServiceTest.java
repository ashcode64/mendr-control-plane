package com.selfhealing.analysis.service.regression;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegressionHarnessServiceTest {

    @Test
    void seededCorpusAllPassWithoutSpring() {
        // Light check: corpus scenarios are well-formed and MockAnalysis agrees
        List<RegressionSeedCorpus.Scenario> scenarios = RegressionSeedCorpus.scenarios();
        assertFalse(scenarios.isEmpty());
        for (RegressionSeedCorpus.Scenario s : scenarios) {
            var structured = com.selfhealing.analysis.service.context.StructuredContextAssembler.assemble(s.ctx());
            var result = com.selfhealing.analysis.service.MockAnalysis.build(structured, s.ctx(), "test");
            assertTrue(s.expectedRuleType().equals(result.ruleType()),
                    s.name() + " expected " + s.expectedRuleType() + " got " + result.ruleType());
        }
    }
}
