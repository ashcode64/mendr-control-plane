package com.selfhealing.analysis.service.safety;

import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.observability.MendrErrorSemantics;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 8 Safety Gate spine.
 *
 * <p>Status policy (locked):
 * <ol>
 *   <li>{@code refuseAutoHeal} → always {@code PENDING_APPROVAL}</li>
 *   <li>Non-deployable → {@code PENDING_APPROVAL} if HITL, else {@code REJECTED}</li>
 *   <li>Wide Venn-Abers interval → {@code PENDING_APPROVAL}</li>
 *   <li>Conformal abstain → {@code PENDING_APPROVAL}</li>
 *   <li>Accept + auto-apply off → {@code PENDING_APPROVAL} + {@code autoEligible}</li>
 *   <li>Accept + auto-apply on → {@code APPROVED}</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SafetyGateService {

    private final ConformalCalibrationService calibrationService;
    private final MendrErrorSemantics errorSemantics;

    @Value("${mendr.conformal.auto-apply-enabled:false}")
    private boolean autoApplyEnabled;

    @Value("${mendr.conformal.va-max-width:0.25}")
    private double vaMaxWidth;

    @Value("${mendr.confidence.debate-enabled:false}")
    private boolean debateEnabled;

    public SafetyGateResult evaluate(
            boolean refuseAutoHeal,
            boolean validationFailed,
            boolean routingUndeployable,
            boolean effectEffective,
            boolean hitlReview,
            SafetyScore score) {

        // s₇ stub — never runs until flag + future implementation.
        if (DebateStabilitySignal.shouldRun(debateEnabled, effectEffective && !validationFailed)) {
            DebateStabilitySignal.stubScore(true);
        }

        ConformalDecision conformal = calibrationService.decide(score);
        Map<String, Object> extras = new LinkedHashMap<>();
        SafetyGateResult result;

        if (refuseAutoHeal) {
            extras.put("safetyGateReason", "refuseAutoHeal");
            result = new SafetyGateResult(
                    AnalysisResult.AnalysisStatus.PENDING_APPROVAL,
                    score, forcePendingConformal(conformal), extras);
            errorSemantics.recordSafetyGate("refuseAutoHeal", result.status().name());
            return result;
        }

        boolean deployable = !validationFailed && !routingUndeployable && effectEffective;
        if (!deployable) {
            extras.put("safetyGateReason", validationFailed ? "validationFailed"
                    : routingUndeployable ? "routingUndeployable" : "noOpEffect");
            if (hitlReview) {
                result = new SafetyGateResult(
                        AnalysisResult.AnalysisStatus.PENDING_APPROVAL,
                        score, forcePendingConformal(conformal), extras);
            } else {
                result = new SafetyGateResult(
                        AnalysisResult.AnalysisStatus.REJECTED,
                        score, conformal, extras);
            }
            errorSemantics.recordSafetyGate(String.valueOf(extras.get("safetyGateReason")),
                    result.status().name());
            return result;
        }

        if (score != null && score.wideInterval(vaMaxWidth)) {
            extras.put("safetyGateReason", "vennAbersWideInterval");
            extras.put("vaWidth", score.intervalWidth());
            extras.put("vaMaxWidth", vaMaxWidth);
            extras.put("vennAbersFitted", score.vennAbersFitted());
            result = new SafetyGateResult(
                    AnalysisResult.AnalysisStatus.PENDING_APPROVAL,
                    score, forcePendingConformal(conformal), extras);
            errorSemantics.recordSafetyGate("vennAbersWideInterval", result.status().name());
            errorSemantics.recordSelectivePrediction(true, conformal.abstain(), false);
            errorSemantics.recordMetamorphic(score.metamorphicPassRate());
            return result;
        }

        if (conformal.abstain()) {
            extras.put("safetyGateReason", "conformalAbstain");
            result = new SafetyGateResult(
                    AnalysisResult.AnalysisStatus.PENDING_APPROVAL,
                    score, conformal, extras);
            errorSemantics.recordSafetyGate("conformalAbstain", result.status().name());
            errorSemantics.recordSelectivePrediction(false, true, false);
            errorSemantics.recordMetamorphic(score.metamorphicPassRate());
            return result;
        }

        if (autoApplyEnabled) {
            extras.put("safetyGateReason", "conformalAcceptAutoApply");
            result = new SafetyGateResult(
                    AnalysisResult.AnalysisStatus.APPROVED,
                    score, conformal, extras);
        } else {
            extras.put("safetyGateReason", "conformalAcceptPendingReview");
            result = new SafetyGateResult(
                    AnalysisResult.AnalysisStatus.PENDING_APPROVAL,
                    score, conformal, extras);
        }
        errorSemantics.recordSafetyGate(String.valueOf(extras.get("safetyGateReason")),
                result.status().name());
        errorSemantics.recordSelectivePrediction(false, false, conformal.autoEligible());
        errorSemantics.recordMetamorphic(score.metamorphicPassRate());
        return result;
    }

    public SafetyScore buildScore(
            double modelConfidence,
            double deterministicAgreement,
            Double metamorphicPassRate,
            Double specTrust,
            double precedentQuality) {
        return buildScore(modelConfidence, deterministicAgreement, metamorphicPassRate,
                specTrust, precedentQuality, 0.5, 0.5);
    }

    public SafetyScore buildScore(
            double modelConfidence,
            double deterministicAgreement,
            Double metamorphicPassRate,
            Double specTrust,
            double precedentQuality,
            double semanticConsistency) {
        return buildScore(modelConfidence, deterministicAgreement, metamorphicPassRate,
                specTrust, precedentQuality, semanticConsistency, 0.5);
    }

    public SafetyScore buildScore(
            double modelConfidence,
            double deterministicAgreement,
            Double metamorphicPassRate,
            Double specTrust,
            double precedentQuality,
            double semanticConsistency,
            double causalVerification) {
        double meta = metamorphicPassRate == null ? 0.5 : metamorphicPassRate;
        double trust = specTrust == null ? 0.5 : specTrust;
        return calibrationService.score(
                modelConfidence, deterministicAgreement, meta, trust,
                precedentQuality, semanticConsistency, causalVerification);
    }

    private static ConformalDecision forcePendingConformal(ConformalDecision c) {
        return new ConformalDecision(
                c.abstain(),
                c.riskBound(),
                false,
                c.calibrationVersion(),
                c.quantileHat(),
                c.nonconformityScore(),
                c.crcFeasible());
    }
}
