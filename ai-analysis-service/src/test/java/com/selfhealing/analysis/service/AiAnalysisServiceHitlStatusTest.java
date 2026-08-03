package com.selfhealing.analysis.service;

import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.observability.MendrErrorSemantics;
import com.selfhealing.analysis.service.safety.ConformalCalibrationService;
import com.selfhealing.analysis.service.safety.ConformalDecision;
import com.selfhealing.analysis.service.safety.SafetyGateResult;
import com.selfhealing.analysis.service.safety.SafetyGateService;
import com.selfhealing.analysis.service.safety.SafetyScore;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * HITL / diagnose interpretation tests. Approval status is owned by {@link SafetyGateService}
 * (not a verbalized confidence threshold).
 */
class AiAnalysisServiceHitlStatusTest {

    private SafetyGateService gate;

    @BeforeEach
    void setUp() {
        ConformalCalibrationService calibration = mock(ConformalCalibrationService.class);
        MendrErrorSemantics metrics = mock(MendrErrorSemantics.class);
        gate = new SafetyGateService(calibration, metrics);
        ReflectionTestUtils.setField(gate, "autoApplyEnabled", false);
        ReflectionTestUtils.setField(gate, "vaMaxWidth", 0.25);
        ReflectionTestUtils.setField(gate, "debateEnabled", false);
        doReturn(new ConformalDecision(false, 0.01, true, "t", 0.5, 0.05, true))
                .when(calibration).decide(any());
    }

    private static SafetyScore narrow() {
        return new SafetyScore(0.9, 0.9, 1.0, 0.9, 0.9, 0.9, 0.9, 0.05,
                0.95, 0.4, 0.5, 0.45, 0.10, true);
    }

    @Test
    void refuseAutoHealForcesPendingApprovalEvenWhenValidationFails() {
        SafetyGateResult r = gate.evaluate(true, true, false, false, true, narrow());
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
    }

    @Test
    void refuseAutoHealForcesPendingApprovalEvenWhenNoOpEffect() {
        SafetyGateResult r = gate.evaluate(true, false, false, false, true, narrow());
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
    }

    @Test
    void refuseAutoHealForcesPendingApprovalWhenRoutingUndeployable() {
        SafetyGateResult r = gate.evaluate(true, false, true, true, true, narrow());
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
    }

    @Test
    void withoutHitlRejectsOnValidationFailure() {
        SafetyGateResult r = gate.evaluate(false, true, false, true, false, narrow());
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.REJECTED);
    }

    @Test
    void withoutHitlDeployableIsPendingWhenAutoApplyOff() {
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, narrow());
        assertThat(r.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
        assertThat(r.conformal().autoEligible()).isTrue();
    }

    @Test
    void nonReadyDiagnoseWithoutRefuseFallsThroughToLlm() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "unverifiable");
        body.put("refuseAutoHeal", false);
        assertThat(AiAnalysisService.interpretDiagnoseResponse(body)).isNull();
    }

    @Test
    void nonReadyDiagnoseWithRefuseReturnsHitlResultWithoutProgram() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "unverifiable");
        body.put("refuseAutoHeal", true);
        body.put("owner_action_required", true);
        body.put("lagReason", "upstream lag detected");
        body.put("confidence", 0.35);
        body.put("rationale", "synthesis failed");
        body.put("model", "conversation-engine");
        body.put("diagnosis", Map.of(
                "refuseAutoHeal", true,
                "lagEvidence", java.util.List.of(Map.of("hop", "a→b"))
        ));

        AnalysisToolResult result = AiAnalysisService.interpretDiagnoseResponse(body);
        assertThat(result).isNotNull();
        assertThat(result.ruleType()).isEqualTo("HITL_REVIEW");
        assertThat(result.transformationRules()).containsEntry("_refuseAutoHeal", true);
        assertThat(result.transformationRules()).containsEntry("_owner_action_required", true);
        assertThat(result.transformationRules()).containsEntry("_lagReason", "upstream lag detected");
        assertThat(result.transformationRules()).containsKey("_lagEvidence");
        assertThat(result.rootCause()).contains("HITL required");
        assertThat(result.suggestedPermanentFix()).contains("do not auto-heal");

        SafetyGateResult status = gate.evaluate(true, true, false, false, true, narrow());
        assertThat(status.status()).isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
    }

    @Test
    void readyDiagnoseWithRefuseKeepsProgramAndHitlFlags() {
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("ops", java.util.List.of(Map.of("op", "rename", "from", "/a", "to", "/b")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ready");
        body.put("program", program);
        body.put("refuseAutoHeal", true);
        body.put("owner_action_required", true);
        body.put("confidence", 0.4);
        body.put("simulation", Map.of("results", java.util.List.of()));
        body.put("tokenLogprobs", java.util.List.of(-0.1, -0.2, -0.15));

        AnalysisToolResult result = AiAnalysisService.interpretDiagnoseResponse(body);
        assertThat(result).isNotNull();
        assertThat(result.ruleType()).isEqualTo("DSL_PROGRAM");
        assertThat(result.transformationRules()).containsEntry("type", "DSL_PROGRAM");
        assertThat(result.transformationRules()).containsEntry("_refuseAutoHeal", true);
        assertThat(result.transformationRules()).containsKey("_simulation");
        assertThat(result.transformationRules()).containsKey("_tokenLogprobs");
    }

    @Test
    void diagnoseRefuseReadsNestedDiagnosisMap() {
        Map<String, Object> body = Map.of(
                "status", "unverifiable",
                "diagnosis", Map.of("owner_action_required", true)
        );
        assertThat(AiAnalysisService.diagnoseRefuse(body)).isTrue();
        assertThat(AiAnalysisService.interpretDiagnoseResponse(body)).isNotNull();
    }
}
