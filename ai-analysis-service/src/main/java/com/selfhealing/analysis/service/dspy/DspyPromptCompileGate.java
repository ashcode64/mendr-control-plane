package com.selfhealing.analysis.service.dspy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 8.9 / Phase 6 — DSPy flag gate (PII scrub approval).
 * Prefer {@link com.selfhealing.analysis.service.gepa.GepaCompileGate} for compile eligibility
 * (also requires scrub-proven COMPLETED offline payloads).
 */
@Slf4j
@Component
public class DspyPromptCompileGate {

    @Value("${mendr.dspy.enabled:false}")
    private boolean enabled;

    @Value("${mendr.dspy.pii-scrub-approved:false}")
    private boolean piiScrubApproved;

    public boolean canBuildOfflineDataset() {
        if (!enabled) {
            log.debug("DSPy disabled (mendr.dspy.enabled=false)");
            return false;
        }
        if (!piiScrubApproved) {
            log.warn("DSPy blocked: mendr.dspy.pii-scrub-approved must be true after dedicated PII scrub design");
            return false;
        }
        return true;
    }

    public String status() {
        if (!enabled) return "disabled";
        if (!piiScrubApproved) return "blocked_pending_pii_scrub";
        return "ready";
    }
}
