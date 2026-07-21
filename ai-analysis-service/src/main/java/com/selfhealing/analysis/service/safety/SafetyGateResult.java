package com.selfhealing.analysis.service.safety;

import com.selfhealing.analysis.model.AnalysisResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Status + metadata produced by {@link SafetyGateService#evaluate}.
 */
public record SafetyGateResult(
        AnalysisResult.AnalysisStatus status,
        SafetyScore score,
        ConformalDecision conformal,
        Map<String, Object> metadataExtras) {

    public Map<String, Object> mergeInto(Map<String, Object> meta) {
        Map<String, Object> out = meta == null ? new LinkedHashMap<>() : meta;
        if (score != null) out.put("safetyScore", score.toMap());
        if (conformal != null) {
            out.put("conformal", conformal.toMap());
            out.put("autoEligible", conformal.autoEligible());
            if (conformal.abstain()) out.put("conformal.abstain", true);
        }
        if (metadataExtras != null) out.putAll(metadataExtras);
        return out;
    }
}
