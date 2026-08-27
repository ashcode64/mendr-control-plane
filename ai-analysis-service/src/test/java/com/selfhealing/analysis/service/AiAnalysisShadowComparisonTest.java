package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiAnalysisShadowComparisonTest {

    @Test
    void shadowComparisonFlagsMatchingOps() {
        Map<String, Object> detector = Map.of(
                "ops", List.of(Map.of("op", "scale", "path", "/x", "numerator", 2, "denominator", 1)));
        Map<String, Object> llm = Map.of(
                "type", "DSL_PROGRAM",
                "ops", List.of(Map.of("op", "scale", "path", "/x", "numerator", 2, "denominator", 1)));
        Map<String, Object> shadow = AiAnalysisService.buildShadowComparison(detector, llm);
        assertEquals(true, shadow.get("programsMatch"));
        assertEquals(true, shadow.get("detectorBeatsOrTiesLlm"));
    }

    @Test
    void shadowComparisonDoesNotVacuousBeatWhenLlmOpsMissing() {
        Map<String, Object> detector = Map.of("ops", List.of(Map.of("op", "rename", "from", "/a", "to", "/b")));
        Map<String, Object> llm = Map.of("type", "FIELD_RENAME");
        Map<String, Object> shadow = AiAnalysisService.buildShadowComparison(detector, llm);
        assertEquals(false, shadow.get("programsMatch"));
        assertEquals(false, shadow.get("detectorBeatsOrTiesLlm"));
        assertEquals(true, shadow.get("llmMissing"));
    }
}
