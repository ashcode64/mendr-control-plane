package com.selfhealing.analysis.service.tool;

import com.selfhealing.analysis.service.safety.DeterministicProposalGate;
import com.selfhealing.analysis.service.safety.SafetyGateResult;
import com.selfhealing.analysis.model.AnalysisResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves DeterministicProposalGate decisions when gateway verify/simulate/metamorphic
 * return live JSON (MockWebServer) — not hardcoded verifierOk flags alone.
 */
class DeterministicRegistryGatewayIntegrationTest {

    private MockWebServer server;
    private MendrScriptGatewayClient client;
    private DeterministicProposalGate gate;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new MendrScriptGatewayClient(WebClient.builder(), server.url("/").toString(), "test-key");
        gate = new DeterministicProposalGate();
        ReflectionTestUtils.setField(gate, "deterministicAutoApplyEnabled", true);
        ReflectionTestUtils.setField(gate, "unitScaleEnabled", true);
        ReflectionTestUtils.setField(gate, "dateFormatEnabled", true);
        ReflectionTestUtils.setField(gate, "ruleDenylistCsv", "");
        ReflectionTestUtils.setField(gate, "metamorphicMinPassRate", 0.9);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void liveVerifySimulateMetamorphicApproveWhenGatewayGreen() {
        server.enqueue(new MockResponse().setBody("{\"valid\":true}").addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody("{\"output\":{\"speed_mph\":62.1}}").addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody("{\"passed\":true,\"passRate\":1.0}").addHeader("Content-Type", "application/json"));

        Map<String, Object> program = Map.of(
                "schemaVersion", "mendrscript/v1",
                "ops", List.of(Map.of(
                        "op", "scale", "path", "/speed_mph",
                        "numerator", 0.621371, "denominator", 1,
                        "expectedMin", -1e6, "expectedMax", 1e6)));

        Map<String, Object> verify = client.verify(program);
        Map<String, Object> sim = client.simulate(Map.of("program", program, "payload", Map.of("speed_kmh", 100)));
        Map<String, Object> meta = client.verifyProperties(Map.of("program", program, "payload", Map.of()));

        boolean verifierOk = Boolean.TRUE.equals(verify.get("valid"));
        boolean simulationOk = sim.containsKey("output");
        boolean metamorphicOk = Boolean.TRUE.equals(meta.get("passed"))
                || gate.metamorphicPasses(meta.get("passRate") instanceof Number n ? n.doubleValue() : null);

        assertTrue(verifierOk);
        assertTrue(simulationOk);
        assertTrue(metamorphicOk);

        SafetyGateResult r = gate.evaluate(false, false, false, true, true,
                "kmh_to_mph", "UNIT_SCALE", verifierOk, simulationOk, metamorphicOk);
        assertEquals(AnalysisResult.AnalysisStatus.APPROVED, r.status());
    }

    @Test
    void unreachableVerifyFailsClosed() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"valid\":false}"));
        Map<String, Object> verify = client.verify(Map.of("ops", List.of()));
        // Client may still surface valid:false on HTTP error path
        boolean verifierOk = Boolean.TRUE.equals(verify.get("valid"));
        assertFalse(verifierOk);
        SafetyGateResult r = gate.evaluate(false, false, false, true, true,
                "kmh_to_mph", "UNIT_SCALE", false, true, true);
        assertEquals("deterministicVerifierFailed", r.metadataExtras().get("safetyGateReason"));
    }

    @Test
    void killSwitchDrillBeforePromotion() {
        // Drill: auto-apply on, then denylist the rule → must PENDING; then re-enable.
        ReflectionTestUtils.setField(gate, "deterministicAutoApplyEnabled", true);
        SafetyGateResult ok = gate.evaluate(false, false, false, true, true,
                "kmh_to_mph", "UNIT_SCALE", true, true, true);
        assertEquals(AnalysisResult.AnalysisStatus.APPROVED, ok.status());

        ReflectionTestUtils.setField(gate, "ruleDenylistCsv", "kmh_to_mph");
        SafetyGateResult denied = gate.evaluate(false, false, false, true, true,
                "kmh_to_mph", "UNIT_SCALE", true, true, true);
        assertEquals(AnalysisResult.AnalysisStatus.PENDING_APPROVAL, denied.status());
        assertEquals("deterministicRuleDenylisted", denied.metadataExtras().get("safetyGateReason"));

        ReflectionTestUtils.setField(gate, "ruleDenylistCsv", "");
        ReflectionTestUtils.setField(gate, "unitScaleEnabled", false);
        SafetyGateResult kindOff = gate.evaluate(false, false, false, true, true,
                "other", "UNIT_SCALE", true, true, true);
        assertEquals("deterministicDetectorDisabled", kindOff.metadataExtras().get("safetyGateReason"));
    }
}
