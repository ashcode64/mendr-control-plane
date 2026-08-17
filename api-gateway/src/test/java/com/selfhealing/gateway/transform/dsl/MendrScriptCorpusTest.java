package com.selfhealing.gateway.transform.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared P5 corpus: the same JSON cases the Lua splice_spec runs against
 * {@code apply_program}. This side uses {@link MendrScriptExecutor}.
 */
class MendrScriptCorpusTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MendrScriptExecutor executor = new MendrScriptExecutor();
    private final MendrScriptVerifier verifier = new MendrScriptVerifier();

    @Test
    void corpusMatchesJavaExecutor() throws Exception {
        InputStream in = getClass().getResourceAsStream("/mendrscript/corpus.json");
        assertNotNull(in, "missing /mendrscript/corpus.json");
        JsonNode cases = mapper.readTree(in);
        assertTrue(cases.isArray() && cases.size() > 0);
        for (JsonNode c : cases) {
            if (c.path("luaOnly").asBoolean(false)) {
                continue;
            }
            String name = c.get("name").asText();
            String body = c.get("body").asText();
            boolean failClosed = c.path("failClosed").asBoolean(false);
            JsonNode ops = c.get("ops");
            var root = mapper.createObjectNode();
            root.put("schemaVersion", MendrProgram.CURRENT_SCHEMA);
            root.set("ops", ops);
            MendrProgram program = mapper.treeToValue(root, MendrProgram.class);
            assertTrue(verifier.verify(program).valid(), name + " must verify");
            Map<?, ?> input = mapper.readValue(body, Map.class);
            if (failClosed) {
                assertThrows(MendrScriptRuntimeException.class,
                        () -> executor.execute(program, input), name);
            } else {
                Object out = executor.execute(program, mapper.readValue(body, Map.class));
                assertNotNull(out, name);
                // Semantic parity against the SHARED expected output that the Lua
                // splice_spec also asserts — this is the cross-runtime oracle.
                JsonNode expected = c.get("expected");
                assertNotNull(expected, name + " must carry an expected output");
                assertTrue(semanticEquals(expected, mapper.valueToTree(out)),
                        name + " wire shape: expected " + expected + " got " + mapper.valueToTree(out));
            }
        }
    }

    /**
     * Value-based JSON comparison shared with the Lua {@code deep_eq} oracle:
     * numbers compare by decimal value (so {@code 25} == {@code 25.0}), objects
     * are order-independent, arrays are positional.
     */
    private static boolean semanticEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) return a == b;
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
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
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!semanticEquals(a.get(i), b.get(i))) return false;
            }
            return true;
        }
        return a.equals(b);
    }
}
