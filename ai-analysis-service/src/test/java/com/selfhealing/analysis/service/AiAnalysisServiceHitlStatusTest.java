package com.selfhealing.analysis.service;

import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisServiceHitlStatusTest {

    @Test
    void refuseAutoHealForcesPendingApprovalEvenWhenValidationFails() {
        assertThat(AiAnalysisService.resolveApprovalStatus(
                true, true, false, false, 0.1, 0.75))
                .isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
    }

    @Test
    void refuseAutoHealForcesPendingApprovalEvenWhenNoOpEffect() {
        assertThat(AiAnalysisService.resolveApprovalStatus(
                true, false, false, false, 0.99, 0.75))
                .isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
    }

    @Test
    void refuseAutoHealForcesPendingApprovalWhenRoutingUndeployable() {
        assertThat(AiAnalysisService.resolveApprovalStatus(
                true, false, true, true, 0.99, 0.75))
                .isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
    }

    @Test
    void withoutHitlRejectsOnValidationFailure() {
        assertThat(AiAnalysisService.resolveApprovalStatus(
                false, true, false, true, 0.99, 0.75))
                .isEqualTo(AnalysisResult.AnalysisStatus.REJECTED);
    }

    @Test
    void withoutHitlApprovesWhenDeployableAndAboveThreshold() {
        assertThat(AiAnalysisService.resolveApprovalStatus(
                false, false, false, true, 0.9, 0.75))
                .isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
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

        // End-to-end status with the extracted flags
        assertThat(AiAnalysisService.resolveApprovalStatus(
                true, true, false, false, result.confidence(), 0.75))
                .isEqualTo(AnalysisResult.AnalysisStatus.PENDING_APPROVAL);
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

        AnalysisToolResult result = AiAnalysisService.interpretDiagnoseResponse(body);
        assertThat(result).isNotNull();
        assertThat(result.ruleType()).isEqualTo("DSL_PROGRAM");
        assertThat(result.transformationRules()).containsEntry("type", "DSL_PROGRAM");
        assertThat(result.transformationRules()).containsEntry("_refuseAutoHeal", true);
        assertThat(result.transformationRules()).containsKey("_simulation");
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
