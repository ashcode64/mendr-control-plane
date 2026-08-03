package com.selfhealing.analysis.service.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.tool.MendrScriptGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Behavioral cluster keys: prefer byte-identical {@code simulate_transform} outputs
 * across shared cases; fall back to structural {@link CanonicalAstHasher#hashProgram}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehavioralClusterer {

    private final MendrScriptGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;

    /**
     * @return one cluster hash per program (same length as {@code programs})
     */
    public List<String> clusterHashes(
            List<Map<String, Object>> programs,
            List<Map<String, Object>> cases) {
        List<String> out = new ArrayList<>();
        if (programs == null || programs.isEmpty()) return out;
        boolean canSimulate = cases != null && !cases.isEmpty();
        for (Map<String, Object> program : programs) {
            if (program == null) {
                out.add("null");
                continue;
            }
            String behavioral = canSimulate ? simulateFingerprint(program, cases) : null;
            if (behavioral != null && !behavioral.isBlank()) {
                out.add(CanonicalAstHasher.hashBytes(behavioral));
            } else {
                out.add(CanonicalAstHasher.hashProgram(program));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String simulateFingerprint(Map<String, Object> program, List<Map<String, Object>> cases) {
        try {
            Map<String, Object> result = gatewayClient.simulate(Map.of(
                    "program", program,
                    "cases", cases));
            if (result == null || result.containsKey("error")) return null;
            Object outputs = result.get("outputs");
            if (outputs == null) outputs = result.get("results");
            if (outputs == null) outputs = result.get("cases");
            if (outputs == null) {
                // Some gateways return the transformed payloads under "output".
                outputs = result.get("output");
            }
            if (outputs == null) return null;
            return objectMapper.writeValueAsString(outputs);
        } catch (Exception e) {
            log.debug("behavioral simulate fingerprint skipped: {}", e.getMessage());
            return null;
        }
    }
}
