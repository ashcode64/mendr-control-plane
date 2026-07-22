package com.selfhealing.analysis.service;

import com.selfhealing.analysis.service.context.StructuredContextAssembler;
import com.selfhealing.analysis.service.context.StructuredFailureContext;
import com.selfhealing.analysis.service.regression.RegressionSeedCorpus;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;
import com.selfhealing.analysis.service.tool.AnalysisTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression corpus: seeded scenarios via {@link RegressionSeedCorpus}.
 * Production {@link com.selfhealing.analysis.service.regression.RegressionHarnessService}
 * reuses the same seeds and gates promotions.
 */
class AnalysisRegressionHarnessTest {

    private static final String MODEL = "test-model";

    @Test
    @DisplayName("regression corpus: each seeded failure produces its expected rule type")
    void corpusProducesExpectedRules() {
        for (RegressionSeedCorpus.Scenario s : RegressionSeedCorpus.scenarios()) {
            StructuredFailureContext structured = StructuredContextAssembler.assemble(s.ctx());
            AnalysisToolResult result = MockAnalysis.build(structured, s.ctx(), MODEL);

            assertNotNull(result.ruleType(), s.name() + ": rule type must not be null");
            assertEquals(s.expectedRuleType(), result.ruleType(), s.name() + ": rule type");
            assertEquals(s.expectedRuleType(), result.transformationRules().get("type"),
                    s.name() + ": transformationRules.type");
            assertNotNull(AnalysisTools.toolForRuleType(result.ruleType()),
                    s.name() + ": rule type must map to a known tool");
        }
    }

    @Test
    @DisplayName("every category routes to a defined tool set")
    void everyCategoryHasTools() {
        for (String category : List.of(
                "SCHEMA_MISMATCH", "RESPONSE_MISMATCH", "ROUTING", "CORS", "CORS_UPSTREAM", "UNKNOWN")) {
            assertTrue(AnalysisTools.toolsForCategory(category).size() >= 1,
                    category + " must have at least one tool");
        }
    }
}
