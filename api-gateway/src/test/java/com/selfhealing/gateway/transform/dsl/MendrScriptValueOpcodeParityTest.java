package com.selfhealing.gateway.transform.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 parity cases for opcodes emitted by UNIT_SCALE / DATE_FORMAT detectors.
 */
class MendrScriptValueOpcodeParityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MendrScriptExecutor executor = new MendrScriptExecutor();
    private final MendrScriptVerifier verifier = new MendrScriptVerifier();

    @Test
    void reformatDateEpochSToIso() throws Exception {
        run(Map.of("ts", 1700000000),
                List.of(Map.of("op", "reformat_date", "path", "/ts",
                        "sourceFormat", "epoch_s", "targetFormat", "iso8601")),
                Map.of("ts", "2023-11-14T22:13:20Z"));
    }

    @Test
    void reformatDateIsoToEpochS() throws Exception {
        run(Map.of("ts", "2023-11-14T22:13:20Z"),
                List.of(Map.of("op", "reformat_date", "path", "/ts",
                        "sourceFormat", "iso8601", "targetFormat", "epoch_s")),
                Map.of("ts", 1700000000));
    }

    @Test
    void reformatDateIsoToEpochMs() throws Exception {
        run(Map.of("ts", "2020-01-01T00:00:00Z"),
                List.of(Map.of("op", "reformat_date", "path", "/ts",
                        "sourceFormat", "iso8601", "targetFormat", "epoch_ms")),
                Map.of("ts", 1577836800000L));
    }

    @Test
    void scaleKmhFactor() throws Exception {
        Map<String, Object> scale = new LinkedHashMap<>();
        scale.put("op", "scale");
        scale.put("path", "/speed");
        scale.put("numerator", 0.621371);
        scale.put("denominator", 1);
        scale.put("expectedMin", 0);
        scale.put("expectedMax", 1_000_000);
        run(Map.of("speed", 100), List.of(scale), Map.of("speed", 62.1371));
    }

    @Test
    void scaleCelsiusThenOffset() throws Exception {
        Map<String, Object> scale = new LinkedHashMap<>();
        scale.put("op", "scale");
        scale.put("path", "/temp");
        scale.put("numerator", 9);
        scale.put("denominator", 5);
        scale.put("expectedMin", -1000);
        scale.put("expectedMax", 10000);
        Map<String, Object> arith = new LinkedHashMap<>();
        arith.put("op", "arith");
        arith.put("path", "/temp");
        arith.put("operator", "+");
        arith.put("operand", 32);
        arith.put("expectedMin", -1000);
        arith.put("expectedMax", 10000);
        run(Map.of("temp", 0), List.of(scale, arith), Map.of("temp", 32));
    }

    private void run(Map<String, Object> input, List<Map<String, Object>> ops,
                     Map<String, Object> expected) throws Exception {
        MendrProgram program = mapper.convertValue(
                Map.of("schemaVersion", MendrProgram.CURRENT_SCHEMA, "ops", ops),
                MendrProgram.class);
        var vr = verifier.verify(program);
        assertTrue(vr.valid(), "must verify: " + ops + " errors=" + vr.errors());
        Object out = executor.execute(program, input);
        JsonNode exp = mapper.valueToTree(expected);
        JsonNode got = mapper.valueToTree(out);
        assertTrue(semanticEquals(exp, got), "expected " + exp + " got " + got);
    }

    private static boolean semanticEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) return a == b;
        if (a.isNumber() && b.isNumber()) {
            return Math.abs(a.asDouble() - b.asDouble()) < 1e-9;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            var fields = a.fieldNames();
            while (fields.hasNext()) {
                String f = fields.next();
                if (!b.has(f) || !semanticEquals(a.get(f), b.get(f))) return false;
            }
            return true;
        }
        return a.equals(b);
    }
}
