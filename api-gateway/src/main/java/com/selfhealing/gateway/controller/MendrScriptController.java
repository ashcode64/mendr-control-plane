package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.transform.dsl.MendrProgram;
import com.selfhealing.gateway.transform.dsl.MendrScriptVerifier;
import com.selfhealing.gateway.transform.dsl.MetamorphicPropertyVerifier;
import com.selfhealing.gateway.transform.dsl.TransformSimulator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal endpoints (guarded by {@code X-Internal-Api-Key}) that expose the
 * authoritative MendrScript verifier + simulator. The ai-analysis-service MCP tools
 * ({@code verify_program} / {@code simulate_transform}) and the rule-engine deploy
 * re-verify both call these so there is ONE implementation of the edge contract — no
 * drift between what the chatbot is told is safe and what actually deploys.
 */
@RestController
@RequestMapping("/api/internal/mendrscript")
@RequiredArgsConstructor
public class MendrScriptController {

    private final MendrScriptVerifier verifier;
    private final TransformSimulator simulator;
    private final MetamorphicPropertyVerifier metamorphicVerifier;

    @PostMapping("/verify")
    public ResponseEntity<MendrScriptVerifier.VerificationResult> verify(@RequestBody MendrProgram program) {
        return ResponseEntity.ok(verifier.verify(program));
    }

    public record SimulateRequest(MendrProgram program, List<TransformSimulator.Case> cases) {}

    @PostMapping("/simulate")
    public ResponseEntity<?> simulate(@RequestBody SimulateRequest req) {
        MendrScriptVerifier.VerificationResult v = verifier.verify(req.program());
        if (!v.valid()) {
            // never simulate an unverified program — the verifier is the gate
            return ResponseEntity.badRequest().body(v);
        }
        List<TransformSimulator.Case> cases = req.cases() == null ? List.of() : req.cases();
        return ResponseEntity.ok(simulator.simulate(req.program(), cases));
    }

    public record VerifyPropertiesRequest(MendrProgram program, List<Object> inputs) {}

    /** Offline metamorphic / property checks — never live-probes upstreams. */
    @PostMapping("/verify-properties")
    public ResponseEntity<?> verifyProperties(@RequestBody VerifyPropertiesRequest req) {
        MendrScriptVerifier.VerificationResult v = verifier.verify(req.program());
        if (!v.valid()) {
            return ResponseEntity.badRequest().body(v);
        }
        return ResponseEntity.ok(metamorphicVerifier.verifyProperties(
                req.program(), req.inputs() == null ? List.of() : req.inputs()));
    }
}
