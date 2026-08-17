package com.selfhealing.gateway.transform.minimize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.selfhealing.gateway.transform.dsl.MendrProgram;
import com.selfhealing.gateway.transform.dsl.MendrScriptVerifier;
import com.selfhealing.gateway.transform.dsl.MetamorphicPropertyVerifier;
import com.selfhealing.gateway.transform.dsl.Op;
import com.selfhealing.gateway.transform.dsl.TransformSimulator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Facade: call Rust mendr-minimize sidecar, then mandatory Java re-verify.
 * On any failure, fall back to the original verified draft (never trade correctness).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinimizeProgramService {

    private final MendrScriptVerifier verifier;
    private final TransformSimulator simulator;
    private final MetamorphicPropertyVerifier metamorphicVerifier;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${mendr.minimize.base-url:http://mendr-minimize:8099}")
    private String minimizeBaseUrl;

    @Value("${mendr.minimize.enabled:true}")
    private boolean enabled;

    @Value("${mendr.minimize.spec-trust-gate:0.85}")
    private double specTrustGate;

    @Value("${mendr.minimize.prove-max-ops:8}")
    private int proveMaxOps;

    public Map<String, Object> minimize(MinimizeRequest req) {
        MendrProgram draft = req.program();
        if (draft == null || draft.ops() == null) {
            return fallbackMap(null, List.of("missing program"), true);
        }
        int originalCount = draft.ops().size();

        MendrScriptVerifier.VerificationResult draftV = verifier.verify(draft);
        if (!draftV.valid()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("program", draft);
            out.put("minimized", false);
            out.put("layersApplied", List.of());
            out.put("originalOpCount", originalCount);
            out.put("finalOpCount", originalCount);
            out.put("fellBack", true);
            out.put("engine", "none");
            out.put("errors", draftV.errors());
            return out;
        }

        if (!enabled) {
            return identity(draft, originalCount, "disabled", false);
        }

        Map<String, Object> rustResult = callRust(req, draft);
        MendrProgram candidate = draft;
        List<String> layers = List.of();
        boolean rustMinimized = false;
        String engine = "rust";

        if (rustResult != null && rustResult.get("program") != null) {
            try {
                candidate = objectMapper.convertValue(rustResult.get("program"), MendrProgram.class);
                rustMinimized = Boolean.TRUE.equals(rustResult.get("minimized"));
                Object la = rustResult.get("layersApplied");
                if (la instanceof List<?> list) {
                    layers = list.stream().map(String::valueOf).toList();
                }
            } catch (Exception e) {
                log.warn("Failed to parse rust minimize result: {}", e.getMessage());
                return identity(draft, originalCount, "rust_parse_error", true);
            }
        } else {
            return identity(draft, originalCount, "rust_unavailable", true);
        }

        // Accept strictly better cost: fewer ops, or same ops with fewer value-mutating ops.
        if (!rustMinimized || candidate.ops() == null || !isBetter(candidate, draft)) {
            Map<String, Object> out = new LinkedHashMap<>(identity(draft, originalCount, engine, false));
            out.put("layersApplied", layers);
            return out;
        }

        if (!reVerify(candidate, req)) {
            log.info("Minimize re-verify failed — falling back to draft ({} → {} ops rejected)",
                    originalCount, candidate.ops().size());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("program", draft);
            out.put("minimized", false);
            out.put("layersApplied", layers);
            out.put("originalOpCount", originalCount);
            out.put("finalOpCount", originalCount);
            out.put("fellBack", true);
            out.put("draftProgram", draft);
            out.put("engine", engine);
            return out;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("program", candidate);
        out.put("minimized", true);
        out.put("layersApplied", layers);
        out.put("originalOpCount", originalCount);
        out.put("finalOpCount", candidate.ops().size());
        out.put("fellBack", false);
        out.put("draftProgram", draft);
        out.put("engine", engine);
        // Preference pairs only when op counts shrink (same-size valueMutating wins are not logged).
        if (candidate.ops().size() < originalCount) {
            out.put("preferencePair", Map.of("chosen", candidate, "rejected", draft));
        }
        return out;
    }

    /** Lexicographic (opCount, valueMutatingCount) — lower is better.
     * Empty candidate over non-empty draft is rejected (undeployable via approve). */
    static boolean isBetter(MendrProgram candidate, MendrProgram draft) {
        if (candidate == null || candidate.ops() == null) return false;
        if (draft != null && draft.ops() != null && !draft.ops().isEmpty() && candidate.ops().isEmpty()) {
            return false;
        }
        int[] c = cost(candidate);
        int[] d = cost(draft);
        if (c[0] < d[0]) return true;
        return c[0] == d[0] && c[1] < d[1];
    }

    static int[] cost(MendrProgram p) {
        if (p == null || p.ops() == null) return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};
        int mutating = 0;
        for (Op op : p.ops()) {
            if (op.valueMutating()) mutating++;
        }
        return new int[]{p.ops().size(), mutating};
    }

    private boolean reVerify(MendrProgram program, MinimizeRequest req) {
        MendrScriptVerifier.VerificationResult v = verifier.verify(program);
        if (!v.valid()) return false;

        List<TransformSimulator.Case> cases = buildCases(req);
        if (!cases.isEmpty()) {
            TransformSimulator.SimulationReport sim = simulator.simulate(program, cases);
            if (sim.faulted() > 0 || sim.mismatched() > 0) return false;
        }

        List<Object> inputs = cases.stream().map(TransformSimulator.Case::input).toList();
        MetamorphicPropertyVerifier.MetamorphicReport meta =
                metamorphicVerifier.verifyProperties(program, inputs);
        return meta.total() == 0 || meta.allPassed();
    }

    private List<TransformSimulator.Case> buildCases(MinimizeRequest req) {
        List<TransformSimulator.Case> cases = new ArrayList<>();
        if (req.triggeringPayload() != null) {
            cases.add(new TransformSimulator.Case(req.triggeringPayload(), null));
        }
        if (req.cases() != null) {
            for (TransformSimulator.Case c : req.cases()) {
                if (c != null && c.input() != null) {
                    cases.add(c);
                }
            }
        }
        return cases;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callRust(MinimizeRequest req, MendrProgram draft) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.set("program", objectMapper.valueToTree(draft));
            ArrayNode cases = body.putArray("cases");
            for (TransformSimulator.Case c : buildCases(req)) {
                ObjectNode cn = cases.addObject();
                cn.set("input", objectMapper.valueToTree(c.input()));
                if (c.expected() != null) {
                    cn.set("expected", objectMapper.valueToTree(c.expected()));
                }
            }
            if (req.triggeringPayload() != null) {
                body.set("triggeringPayload", objectMapper.valueToTree(req.triggeringPayload()));
            }
            if (req.specTrust() != null) {
                body.put("specTrust", req.specTrust());
            }
            body.put("specTrustGate", specTrustGate);
            body.put("proveMinimalMaxOps", Math.min(proveMaxOps, 8));
            if (req.allowedOpcodes() != null) {
                ArrayNode ao = body.putArray("allowedOpcodes");
                req.allowedOpcodes().forEach(ao::add);
            }
            if (req.declaredFieldTypes() != null && !req.declaredFieldTypes().isEmpty()) {
                ObjectNode types = body.putObject("declaredFieldTypes");
                req.declaredFieldTypes().forEach(types::put);
            }
            if (req.unresolvablePaths() != null && !req.unresolvablePaths().isEmpty()) {
                ArrayNode up = body.putArray("unresolvablePaths");
                req.unresolvablePaths().forEach(up::add);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    minimizeBaseUrl.replaceAll("/$", "") + "/minimize",
                    new HttpEntity<>(body, headers),
                    Map.class);
            return resp.getBody() == null ? null : (Map<String, Object>) resp.getBody();
        } catch (Exception e) {
            log.warn("mendr-minimize sidecar call failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> identity(MendrProgram draft, int n, String engine, boolean fellBack) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("program", draft);
        out.put("minimized", false);
        out.put("layersApplied", List.of());
        out.put("originalOpCount", n);
        out.put("finalOpCount", n);
        out.put("fellBack", fellBack);
        out.put("engine", engine);
        return out;
    }

    private Map<String, Object> fallbackMap(MendrProgram draft, List<String> errors, boolean fellBack) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("program", draft);
        out.put("minimized", false);
        out.put("layersApplied", List.of());
        out.put("originalOpCount", draft == null || draft.ops() == null ? 0 : draft.ops().size());
        out.put("finalOpCount", draft == null || draft.ops() == null ? 0 : draft.ops().size());
        out.put("fellBack", fellBack);
        out.put("engine", "none");
        out.put("errors", errors);
        return out;
    }

    public record MinimizeRequest(
            MendrProgram program,
            List<TransformSimulator.Case> cases,
            Object triggeringPayload,
            Double specTrust,
            List<String> allowedOpcodes,
            Map<String, String> declaredFieldTypes,
            List<String> unresolvablePaths
    ) {}
}
