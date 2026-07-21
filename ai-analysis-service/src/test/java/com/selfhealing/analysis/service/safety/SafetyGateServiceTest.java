package com.selfhealing.analysis.service.safety;

import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.observability.MendrErrorSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class SafetyGateServiceTest {

    private ConformalCalibrationService calibration;
    private SafetyGateService gate;

    @BeforeEach
    void setUp() {
        calibration = mock(ConformalCalibrationService.class);
        MendrErrorSemantics metrics = mock(MendrErrorSemantics.class);
        gate = new SafetyGateService(calibration, metrics);
        ReflectionTestUtils.setField(gate, "autoApplyEnabled", false);

        doAnswer(inv -> {
            SafetyScore s = inv.getArgument(0);
            if (s == null) {
                return new ConformalDecision(true, 0.01, false, "test", 0.35, 0.5, true);
            }
            boolean abstain = s.nonconformityScore() > 0.35;
            return new ConformalDecision(abstain, 0.01, !abstain, "test", 0.35,
                    s.nonconformityScore(), true);
        }).when(calibration).decide(any());

        doAnswer(inv -> {
            double conf = inv.getArgument(0);
            double det = inv.getArgument(1);
            double meta = inv.getArgument(2);
            double trust = inv.getArgument(3);
            double prec = inv.getArgument(4);
            SafetyScore partial = new SafetyScore(conf, det, meta, trust, prec, 0);
            double nc = new LogisticNonconformityModel(
                    LogisticNonconformityModel.DEFAULT_WEIGHTS,
                    LogisticNonconformityModel.DEFAULT_BIAS, "t")
                    .predictFailureProbability(partial.nonconformityFeatures());
            return new SafetyScore(conf, det, meta, trust, prec, nc);
        }).when(calibration).score(
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void refuseAlwaysPendingEvenWhenAutoEligible() {
        ReflectionTestUtils.setField(gate, "autoApplyEnabled", true);
        SafetyScore score = new SafetyScore(0.95, 0.95, 1.0, 0.9, 0.9, 0.05);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(true, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        assertThat(r.conformal().autoEligible()).isFalse();
    }

    @Test
    void conformalAbstainIsPending() {
        SafetyScore score = new SafetyScore(0.4, 0.3, 0.2, 0.3, 0.3, 0.8);
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        assertThat(r.conformal().abstain()).isTrue();
    }

    @Test
    void acceptWithAutoApplyOffIsPendingAutoEligible() {
        SafetyScore score = new SafetyScore(0.95, 0.95, 1.0, 0.9, 0.9, 0.05);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        assertThat(r.conformal().autoEligible()).isTrue();
    }

    @Test
    void acceptWithAutoApplyOnIsApproved() {
        ReflectionTestUtils.setField(gate, "autoApplyEnabled", true);
        SafetyScore score = new SafetyScore(0.95, 0.95, 1.0, 0.9, 0.9, 0.05);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.APPROVED);
    }

    @Test
    void validationFailureRejectsWithoutHitl() {
        SafetyScore score = new SafetyScore(0.95, 0.95, 1.0, 0.9, 0.9, 0.05);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(false, true, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.REJECTED);
    }
}
