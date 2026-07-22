package com.selfhealing.analysis.service.gepa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 6 hard gate: GEPA/MIPRO must not run until scrub is proven and flags are on.
 * Never points compilers at raw {@code api_failures}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GepaCompileGate {

    private final JdbcTemplate jdbcTemplate;

    @Value("${mendr.gepa.enabled:false}")
    private boolean enabled;

    @Value("${mendr.dspy.enabled:false}")
    private boolean dspyEnabled;

    @Value("${mendr.dspy.pii-scrub-approved:false}")
    private boolean piiScrubApproved;

    @Value("${mendr.gepa.min-completed-payloads:5}")
    private int minCompletedPayloads;

    public boolean canCompile() {
        if (!enabled) {
            log.debug("GEPA disabled (mendr.gepa.enabled=false)");
            return false;
        }
        if (!piiScrubApproved) {
            log.warn("GEPA blocked: mendr.dspy.pii-scrub-approved must be true");
            return false;
        }
        if (!scrubProven()) {
            log.warn("GEPA blocked: scrub job not proven (need ≥{} COMPLETED offline payloads)",
                    minCompletedPayloads);
            return false;
        }
        return true;
    }

    /** Prefer DSPy GEPA when both GEPA and dspy flags are on; else MIPRO fallback. */
    public boolean preferDspyGepa() {
        return canCompile() && dspyEnabled;
    }

    public boolean scrubProven() {
        try {
            Integer completed = jdbcTemplate.query(
                    """
                    SELECT COUNT(*)::int FROM offline_regression_payloads
                    WHERE scrub_status = 'COMPLETED'
                    """,
                    rs -> rs.next() ? rs.getInt(1) : 0);
            return completed != null && completed >= minCompletedPayloads;
        } catch (Exception e) {
            log.debug("scrub proven check failed: {}", e.getMessage());
            return false;
        }
    }

    public String status() {
        if (!enabled) return "disabled";
        if (!piiScrubApproved) return "blocked_pending_pii_scrub";
        if (!scrubProven()) return "blocked_scrub_unproven";
        if (dspyEnabled) return "ready_gepa";
        return "ready_mipro_fallback";
    }
}
