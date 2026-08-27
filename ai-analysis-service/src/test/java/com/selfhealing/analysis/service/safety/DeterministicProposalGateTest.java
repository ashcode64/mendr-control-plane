package com.selfhealing.analysis.service.safety;

import com.selfhealing.analysis.model.AnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicProposalGateTest {

    private DeterministicProposalGate gate;

    @BeforeEach
    void setUp() {
        gate = new DeterministicProposalGate();
        ReflectionTestUtils.setField(gate, "deterministicAutoApplyEnabled", false);
        ReflectionTestUtils.setField(gate, "unitScaleEnabled", true);
        ReflectionTestUtils.setField(gate, "dateFormatEnabled", true);
        ReflectionTestUtils.setField(gate, "ruleDenylistCsv", "");
        ReflectionTestUtils.setField(gate, "metamorphicMinPassRate", 0.9);
    }

    private SafetyGateResult okEval(boolean auto) {
        ReflectionTestUtils.setField(gate, "deterministicAutoApplyEnabled", auto);
        return gate.evaluate(false, false, false, true, true, "kmh_to_mph", "UNIT_SCALE",
                true, true, true);
    }

    @Test
    void refuseAlwaysPending() {
        SafetyGateResult r = gate.evaluate(true, false, false, true, true, "kmh_to_mph", "UNIT_SCALE",
                true, true, true);
        assertEquals(AnalysisResult.AnalysisStatus.PENDING_APPROVAL, r.status());
        assertEquals("refuseAutoHeal", r.metadataExtras().get("safetyGateReason"));
    }

    @Test
    void autoApplyOffYieldsPendingEligible() {
        SafetyGateResult r = okEval(false);
        assertEquals(AnalysisResult.AnalysisStatus.PENDING_APPROVAL, r.status());
        assertEquals("deterministicAcceptPendingReview", r.metadataExtras().get("safetyGateReason"));
        assertEquals(true, r.metadataExtras().get("autoEligible"));
        assertEquals(true, r.metadataExtras().get("shadowMode"));
    }

    @Test
    void autoApplyOnYieldsApproved() {
        SafetyGateResult r = okEval(true);
        assertEquals(AnalysisResult.AnalysisStatus.APPROVED, r.status());
        assertEquals("deterministicAcceptAutoApply", r.metadataExtras().get("safetyGateReason"));
    }

    @Test
    void verifierFailureBlocks() {
        SafetyGateResult r = gate.evaluate(false, false, false, true, true, "kmh_to_mph", "UNIT_SCALE",
                false, true, true);
        assertEquals("deterministicVerifierFailed", r.metadataExtras().get("safetyGateReason"));
    }

    @Test
    void simulationFailureBlocks() {
        SafetyGateResult r = gate.evaluate(false, false, false, true, true, "kmh_to_mph", "UNIT_SCALE",
                true, false, true);
        assertEquals("deterministicSimulationFailed", r.metadataExtras().get("safetyGateReason"));
    }

    @Test
    void metamorphicFailureBlocks() {
        SafetyGateResult r = gate.evaluate(false, false, false, true, true, "kmh_to_mph", "UNIT_SCALE",
                true, true, false);
        assertEquals("deterministicMetamorphicFailed", r.metadataExtras().get("safetyGateReason"));
    }

    @Test
    void d7NotFiredBlocks() {
        SafetyGateResult r = gate.evaluate(false, false, false, true, false, "kmh_to_mph", "UNIT_SCALE",
                true, true, true);
        assertEquals("deterministicD7NotMet", r.metadataExtras().get("safetyGateReason"));
    }

    @Test
    void killSwitchDenylistDrill() {
        ReflectionTestUtils.setField(gate, "deterministicAutoApplyEnabled", true);
        ReflectionTestUtils.setField(gate, "ruleDenylistCsv", "kmh_to_mph");
        SafetyGateResult r = okEval(true);
        // denylist still set
        r = gate.evaluate(false, false, false, true, true, "kmh_to_mph", "UNIT_SCALE",
                true, true, true);
        assertEquals("deterministicRuleDenylisted", r.metadataExtras().get("safetyGateReason"));
        ReflectionTestUtils.setField(gate, "unitScaleEnabled", false);
        r = gate.evaluate(false, false, false, true, true, "other", "UNIT_SCALE",
                true, true, true);
        assertEquals("deterministicDetectorDisabled", r.metadataExtras().get("safetyGateReason"));
    }
}
