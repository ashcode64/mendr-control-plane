package com.selfhealing.analysis.service.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

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
        // bootstrap-v0 has crcFeasible=false
        assertThat(d.autoEligible()).isFalse();
        assertThat(d.abstain()).isTrue();
    }

    @Test
    void fitRequiresEnoughExamplesButJobGatesAtMinTrainN() {
        List<ConformalCalibrationService.LabeledExample> small = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            small.add(new ConformalCalibrationService.LabeledExample(
                    new double[]{0.1, 0.1, 0.1, 0.1}, i % 3 == 0));
        }
        var fitted = service.fitAndCalibrate(small, 0.01, "t");
        // Internal fit works on small sets; CRC often infeasible — cold start stays safe
        assertThat(fitted.holdoutN()).isGreaterThan(0);
        assertThat(service.minTrainN()).isEqualTo(500);
    }
}
