package com.selfhealing.analysis.service.safety;

import com.selfhealing.analysis.model.AnalysisResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gate for closed-registry deterministic proposals (UNIT_SCALE / DATE_FORMAT).
 * Does <em>not</em> use LLM GenerationConfidence or the conformal quantile.
 * Does <em>not</em> read {@code mendr.conformal.auto-apply-enabled}.
 *
 * <p>D1 required checks: MendrScriptVerifier-valid, simulation effective,
 * metamorphic pass, D7 fire, refuseAutoHeal / route policy.
 */
@Service
@RequiredArgsConstructor
public class DeterministicProposalGate {

    @Getter
    @Value("${mendr.deterministic.auto-apply-enabled:false}")
    private boolean deterministicAutoApplyEnabled;

    @Value("${mendr.deterministic.detectors.unit-scale.enabled:true}")
    private boolean unitScaleEnabled;

    @Value("${mendr.deterministic.detectors.date-format.enabled:true}")
    private boolean dateFormatEnabled;

    @Value("${mendr.deterministic.rule-denylist:}")
    private String ruleDenylistCsv;

    @Value("${mendr.deterministic.metamorphic-min-pass-rate:0.9}")
    private double metamorphicMinPassRate;

    public boolean isUnitScaleEnabled() {
        return unitScaleEnabled;
    }

    public boolean isDateFormatEnabled() {
        return dateFormatEnabled;
    }

    /** True when still in shadow (proposals must not auto-deploy). */
    public boolean isShadowMode() {
        return !deterministicAutoApplyEnabled;
    }

    public Set<String> denylistedRuleIds() {
        if (ruleDenylistCsv == null || ruleDenylistCsv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(ruleDenylistCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isRuleDenied(String registryRuleId) {
        return registryRuleId != null && denylistedRuleIds().contains(registryRuleId);
    }

    /**
     * @param d7Fired          detector fired under D7 conjunction (must be computed by caller, not hardcoded)
     * @param verifierOk       MendrScriptVerifier (or gateway verify) returned valid
     * @param simulationOk     simulation effective against failing payload
     * @param metamorphicOk    metamorphic pass rate meets threshold (or explicit pass)
     */
    public SafetyGateResult evaluate(
            boolean refuseAutoHeal,
            boolean validationFailed,
            boolean routingUndeployable,
            boolean effectEffective,
            boolean d7Fired,
            String registryRuleId,
            String kind,
            boolean verifierOk,
            boolean simulationOk,
            boolean metamorphicOk) {

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("gatePath", "DETERMINISTIC_REGISTRY");
        extras.put("s1Source", "BY_CONSTRUCTION");
        extras.put("deterministicAutoApplyEnabled", deterministicAutoApplyEnabled);
        extras.put("conformalAutoApplyNotConsulted", true);
        extras.put("registryRuleId", registryRuleId);
        extras.put("registryKind", kind);
        extras.put("shadowMode", isShadowMode());
        extras.put("verifierOk", verifierOk);
        extras.put("simulationOk", simulationOk);
        extras.put("metamorphicOk", metamorphicOk);
        extras.put("d7Fired", d7Fired);

        if (refuseAutoHeal) {
            extras.put("safetyGateReason", "refuseAutoHeal");
            return pending(extras);
        }
        if (isRuleDenied(registryRuleId)) {
            extras.put("safetyGateReason", "deterministicRuleDenylisted");
            return pending(extras);
        }
        if (("UNIT_SCALE".equals(kind) && !unitScaleEnabled)
                || ("DATE_FORMAT".equals(kind) && !dateFormatEnabled)) {
            extras.put("safetyGateReason", "deterministicDetectorDisabled");
            return pending(extras);
        }
        if (!d7Fired) {
            extras.put("safetyGateReason", "deterministicD7NotMet");
            return pending(extras);
        }
        if (!verifierOk) {
            extras.put("safetyGateReason", "deterministicVerifierFailed");
            return pending(extras);
        }
        if (!simulationOk) {
            extras.put("safetyGateReason", "deterministicSimulationFailed");
            return pending(extras);
        }
        if (!metamorphicOk) {
            extras.put("safetyGateReason", "deterministicMetamorphicFailed");
            return pending(extras);
        }

        boolean deployable = !validationFailed && !routingUndeployable && effectEffective;
        if (!deployable) {
            extras.put("safetyGateReason", validationFailed ? "validationFailed"
                    : routingUndeployable ? "routingUndeployable" : "noOpEffect");
            return new SafetyGateResult(
                    AnalysisResult.AnalysisStatus.REJECTED,
                    null, null, extras);
        }

        if (deterministicAutoApplyEnabled) {
            extras.put("safetyGateReason", "deterministicAcceptAutoApply");
            return new SafetyGateResult(
                    AnalysisResult.AnalysisStatus.APPROVED,
                    null, null, extras);
        }
        extras.put("safetyGateReason", "deterministicAcceptPendingReview");
        extras.put("autoEligible", true);
        return pending(extras);
    }

    public boolean metamorphicPasses(Double passRate) {
        return passRate != null && passRate >= metamorphicMinPassRate;
    }

    private static SafetyGateResult pending(Map<String, Object> extras) {
        return new SafetyGateResult(
                AnalysisResult.AnalysisStatus.PENDING_APPROVAL,
                null, null, extras);
    }
}
