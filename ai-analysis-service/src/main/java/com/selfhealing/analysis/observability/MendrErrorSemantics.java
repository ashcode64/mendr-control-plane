package com.selfhealing.analysis.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase 8.8 — OTel-aligned error semantic timers/counters around diagnose,
 * critics, SafetyGate, and ddmin. Uses Micrometer (exported via OTel bridge when configured).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MendrErrorSemantics {

    public static final String ATTR_FAILURE_CATEGORY = "mendr.error.category";
    public static final String ATTR_TEMPLATE_ID = "mendr.error.template_id";
    public static final String ATTR_SAFETY_GATE = "mendr.safety.gate_reason";
    public static final String ATTR_DDMIN_PATH = "mendr.ddmin.path";

    private final MeterRegistry meterRegistry;

    public void recordSafetyGate(String reason, String status) {
        try {
            meterRegistry.counter("mendr.safety_gate.decisions",
                    "reason", nullToUnknown(reason),
                    "status", nullToUnknown(status)).increment();
        } catch (Exception e) {
            log.trace("metrics skip: {}", e.getMessage());
        }
    }

    public void recordDdmin(String path, boolean aborted) {
        try {
            meterRegistry.counter("mendr.ddmin.runs",
                    "path", nullToUnknown(path),
                    "aborted", Boolean.toString(aborted)).increment();
        } catch (Exception e) {
            log.trace("metrics skip: {}", e.getMessage());
        }
    }

    /** Path B live probe burst — tenant-visible ops signal (Micrometer). */
    public void recordDdminLiveProbes(String targetService, String method, int probeCount) {
        try {
            meterRegistry.counter("mendr.ddmin.live_probes",
                    "service", nullToUnknown(targetService),
                    "method", nullToUnknown(method)).increment(probeCount);
        } catch (Exception e) {
            log.trace("metrics skip: {}", e.getMessage());
        }
    }

    public void recordMetamorphic(double passRate) {
        try {
            meterRegistry.summary("mendr.metamorphic.pass_rate").record(passRate);
        } catch (Exception e) {
            log.trace("metrics skip: {}", e.getMessage());
        }
    }

    /** Selective prediction: human-review (wide VA) vs conformal abstain vs auto-eligible. */
    public void recordSelectivePrediction(boolean wideInterval, boolean conformalAbstain, boolean autoEligible) {
        try {
            String outcome = wideInterval ? "human_wide_va"
                    : conformalAbstain ? "human_conformal"
                    : autoEligible ? "auto_eligible" : "pending_review";
            meterRegistry.counter("mendr.confidence.selective",
                    "outcome", outcome).increment();
        } catch (Exception e) {
            log.trace("metrics skip: {}", e.getMessage());
        }
    }

    public Timer.Sample startDiagnose() {
        return Timer.start(meterRegistry);
    }

    public void stopDiagnose(Timer.Sample sample, String category) {
        if (sample == null) return;
        try {
            sample.stop(Timer.builder("mendr.diagnose.duration")
                    .tag("category", nullToUnknown(category))
                    .register(meterRegistry));
        } catch (Exception e) {
            log.trace("metrics skip: {}", e.getMessage());
        }
    }

    private static String nullToUnknown(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
