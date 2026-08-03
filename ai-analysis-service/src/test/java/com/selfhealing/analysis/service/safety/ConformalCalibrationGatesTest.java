package com.selfhealing.analysis.service.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConformalCalibrationGatesTest {

    private ConformalCalibrationService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), (Object[]) org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        service = new ConformalCalibrationService(jdbc, new ObjectMapper());
        ReflectionTestUtils.setField(service, "riskBudget", 0.01);
        ReflectionTestUtils.setField(service, "scoreModel", "logistic");
        ReflectionTestUtils.setField(service, "allowOpaqueModel", false);
        ReflectionTestUtils.setField(service, "minTrainN", 500);
        ReflectionTestUtils.setField(service, "vaMaxWidth", 0.25);
    }

    @Test
    void xgboostBlockedWithoutAllowOpaque() {
        ReflectionTestUtils.setField(service, "scoreModel", "xgboost");
        ReflectionTestUtils.setField(service, "allowOpaqueModel", false);
        assertThat(service.canTrainPreferredModel()).isFalse();
        assertThat(service.preferredModelKind()).isEqualTo("logistic");
    }

    @Test
    void xgboostAllowedWithOpaqueFlag() {
        ReflectionTestUtils.setField(service, "scoreModel", "xgboost");
        ReflectionTestUtils.setField(service, "allowOpaqueModel", true);
        assertThat(service.canTrainPreferredModel()).isTrue();
        assertThat(service.preferredModelKind()).isEqualTo("xgboost");
    }

    @Test
    void bootstrapIsNotAutoEligible() {
        ConformalDecision d = service.decide(new SafetyScore(0.9, 0.9, 0.9, 0.9, 0.5, 0.1));
        assertThat(d.autoEligible()).isFalse();
        assertThat(d.abstain()).isTrue();
    }

    @Test
    void fitRequiresEnoughExamplesButJobGatesAtMinTrainN() {
        List<ConformalCalibrationService.LabeledExample> small = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            small.add(new ConformalCalibrationService.LabeledExample(
                    new double[]{0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1}, i % 3 == 0));
        }
        var fitted = service.fitAndCalibrate(small, 0.01, "t");
        assertThat(fitted.holdoutN()).isGreaterThan(0);
        assertThat(fitted.vennAbers()).isNotNull();
        assertThat(fitted.weightsJson()).containsKeys(
                "auroc", "ece", "ablationAurocDelta", "intervalCoverage",
                "multiprobabilityValidity", "eceVsVerbalized", "selectivePrediction",
                "ablationEceDelta", "calibrationGuard", "reliabilityDiagram", "featureLegend");
        assertThat(fitted.weightsJson().get("ablationAurocDelta")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> ab = (Map<String, Object>) fitted.weightsJson().get("ablationAurocDelta");
        assertThat(ab).containsKey("vennAbersVsRaw");
        assertThat(ab).containsKey("s2_deterministicAgreement");
        @SuppressWarnings("unchecked")
        Map<String, Object> mp = (Map<String, Object>) fitted.weightsJson().get("multiprobabilityValidity");
        assertThat(mp).containsKeys("optimisticAccuracy", "pessimisticAccuracy", "empiricalInIntervalRate");
        @SuppressWarnings("unchecked")
        Map<String, Object> eceV = (Map<String, Object>) fitted.weightsJson().get("eceVsVerbalized");
        assertThat(eceV).containsKeys("eceVa", "eceVerbalized", "eceImprovement");
        @SuppressWarnings("unchecked")
        Map<String, Object> sel = (Map<String, Object>) fitted.weightsJson().get("selectivePrediction");
        assertThat(sel).containsKeys("humanReviewRate", "autoEligibleFraction", "wrongAutoApplyRate");
        assertThat(fitted.weightsJson().get("reliabilityDiagram")).isInstanceOf(java.util.List.class);
        assertThat(fitted.weightsJson().get("calibrationGuard")).isInstanceOf(Map.class);
        assertThat(service.minTrainN()).isEqualTo(500);
    }

    @Test
    void unfittedDisplaysRawCorrectNotBootstrapMidpoint() {
        SafetyScore s = service.score(0.9, 0.9, 0.9, 0.9, 0.5, 0.5, 0.9);
        assertThat(s.vennAbersFitted()).isFalse();
        assertThat(s.intervalWidth()).isEqualTo(1.0);
        assertThat(s.displayConfidence()).isEqualTo(s.rawCorrectProbability());
        assertThat(s.displayConfidence()).isNotEqualTo(0.5);
        assertThat(s.nonconformityFeatures()).hasSize(7);
    }
}
