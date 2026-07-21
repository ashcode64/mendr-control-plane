package com.selfhealing.analysis.service.safety;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Distribution-free risk control outcome for a single analysis.
 * {@code abstain=true} means empirical risk of auto-apply would exceed the budget.
 */
public record ConformalDecision(
        boolean abstain,
        double riskBound,
        boolean autoEligible,
        String calibrationVersion,
        double quantileHat,
        double nonconformityScore,
        boolean crcFeasible) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("abstain", abstain);
        m.put("riskBound", riskBound);
        m.put("autoEligible", autoEligible);
        m.put("calibrationVersion", calibrationVersion);
        m.put("quantileHat", quantileHat);
        m.put("nonconformityScore", nonconformityScore);
        m.put("crcFeasible", crcFeasible);
        return m;
    }
}
