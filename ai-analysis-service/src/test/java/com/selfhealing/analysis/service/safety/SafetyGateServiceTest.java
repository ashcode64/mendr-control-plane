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
        ReflectionTestUtils.setField(gate, "vaMaxWidth", 0.25);
        ReflectionTestUtils.setField(gate, "debateEnabled", false);

        doAnswer(inv -> {
            SafetyScore s = inv.getArgument(0);
            if (s == null) {
                return new ConformalDecision(true, 0.01, false, "test", 0.35, 0.5, true);
            }
            boolean abstain = s.nonconformityScore() > 0.35;
            return new ConformalDecision(abstain, 0.01, !abstain, "test", 0.35,
                    s.nonconformityScore(), true);
        }).when(calibration).decide(any());
    }

    @Test
    void refuseAlwaysPendingEvenWhenAutoEligible() {
        ReflectionTestUtils.setField(gate, "autoApplyEnabled", true);
        SafetyScore score = narrowScore(0.05);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(true, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        assertThat(r.conformal().autoEligible()).isFalse();
    }

    @Test
    void conformalAbstainIsPending() {
        SafetyScore score = narrowScore(0.8);
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        assertThat(r.conformal().abstain()).isTrue();
    }

    @Test
    void wideVennAbersForcesPendingEvenWhenConformalAccepts() {
        ReflectionTestUtils.setField(gate, "vaMaxWidth", 0.25);
        SafetyScore score = new SafetyScore(0.95, 0.95, 1.0, 0.9, 0.9, 0.9, 0.9, 0.05,
                0.95, 0.1, 0.9, 0.55, 0.80, true);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        assertThat(r.metadataExtras().get("safetyGateReason")).isEqualTo("vennAbersWideInterval");
    }

    @Test
    void acceptWithAutoApplyOffIsPendingAutoEligible() {
        SafetyScore score = narrowScore(0.05);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        assertThat(r.conformal().autoEligible()).isTrue();
    }

    @Test
    void acceptWithAutoApplyOnIsApproved() {
        ReflectionTestUtils.setField(gate, "autoApplyEnabled", true);
        SafetyScore score = narrowScore(0.05);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.APPROVED);
    }

    @Test
    void validationFailureRejectsWithoutHitl() {
        SafetyScore score = narrowScore(0.05);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
        SafetyGateResult r = gate.evaluate(false, true, false, true, false, score);
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.REJECTED);
    }

    private static SafetyScore narrowScore(double nc) {
        double raw = 1.0 - nc;
        return new SafetyScore(0.95, 0.95, 1.0, 0.9, 0.9, 0.9, 0.9, nc,
                raw, raw - 0.02, raw + 0.02, raw, 0.04, true);
    }
}
