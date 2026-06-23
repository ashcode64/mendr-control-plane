package com.selfhealing.gateway.transform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.util.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file style tests: streaming output must parse to the same Map as the
 * Map-based engine semantics for flat (rename / coerce / default / remove) rules.
 */
class StreamingJsonTransformerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StreamingJsonTransformer transformer;
    private TransformProgramCompiler compiler;

    @BeforeEach
    void setUp() {
        transformer = new StreamingJsonTransformer();
        compiler = new TransformProgramCompiler();
    }

    @Test
    void renameFieldMatchesMapEngine() throws Exception {
        TransformProgram program = programFromDefinition(Map.of(
                "mappings", Map.of("oldAmount", "amount")));

        Map<String, Object> input = Map.of("oldAmount", 100, "nested", Map.of("x", 1));
        assertMapsEqual(input, program);
    }

    @Test
    void coerceFieldMatchesMapEngine() throws Exception {
        TransformProgram program = programFromDefinition(Map.of(
                "coercions", Map.of("amount", "integer")));

        Map<String, Object> input = Map.of("amount", "42");
        assertMapsEqual(input, program);
    }

    @Test
    void addDefaultMatchesMapEngine() throws Exception {
        TransformProgram program = programFromDefinition(Map.of(
                "defaults", Map.of("currency", "USD")));

        Map<String, Object> input = Map.of("amount", 10);
        assertMapsEqual(input, program);
    }

    @Test
    void removeFieldMatchesMapEngine() throws Exception {
        TransformProgram program = programFromDefinition(Map.of(
                "fields", List.of("debug")));

        Map<String, Object> input = new HashMap<>(Map.of("amount", 10, "debug", true));
        assertMapsEqual(input, program);
    }

    @Test
    void compositeRuleMatchesMapEngine() throws Exception {
        TransformProgram program = programFromDefinition(Map.of(
                "mappings", Map.of("amt", "amount"),
                "defaults", Map.of("currency", "USD"),
                "coercions", Map.of("amount", "integer"),
                "fields", List.of("legacy")));

        Map<String, Object> input = new HashMap<>();
        input.put("amt", "99");
        input.put("legacy", "drop-me");
        input.put("nested", Map.of("items", List.of(1, 2)));
        assertMapsEqual(input, program);
    }

    @Test
    void preservesNestedStructuresUntouched() throws Exception {
        TransformProgram program = programFromDefinition(Map.of(
                "mappings", Map.of("id", "paymentId")));

        Map<String, Object> nested = Map.of(
                "unicode", "café",
                "arr", List.of(Map.of("k", "v")));
        Map<String, Object> input = Map.of("id", "p1", "meta", nested);
        assertMapsEqual(input, program);
    }

    @Test
    void emptyProgramReturnsInputUnchanged() throws Exception {
        byte[] input = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] out = transformer.transform(input, TransformProgram.none());
        assertThat(out).isEqualTo(input);
    }

    @Test
    void nonObjectInputReturnsInputUnchanged() throws Exception {
        TransformProgram program = programFromDefinition(Map.of("mappings", Map.of("a", "b")));
        byte[] input = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
        byte[] out = transformer.transform(input, program);
        assertThat(out).isEqualTo(input);
    }

    private TransformProgram programFromDefinition(Map<String, Object> def) {
        return compiler.compileRequest(List.of(
                com.selfhealing.gateway.model.TransformationRule.builder()
                        .ruleType(com.selfhealing.gateway.model.TransformationRule.RuleType.NESTED_TRANSFORM)
                        .ruleDefinition(def)
                        .build()));
    }

    private void assertMapsEqual(Map<String, Object> input, TransformProgram program) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(input);
        byte[] streamed = transformer.transform(json, program);

        Map<String, Object> expected = applyFlatTransform(input, program);
        Map<String, Object> actual = objectMapper.readValue(streamed, new TypeReference<>() {});

        assertThat(actual).isEqualTo(expected);
    }

    /** Mirrors TransformationEngine.applyRule section order for flat rules. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyFlatTransform(Map<String, Object> payload, TransformProgram program) {
        Map<String, Object> result = new HashMap<>(payload);

        program.getRenames().forEach((from, to) -> {
            if (result.containsKey(from)) {
                result.put(to, result.remove(from));
            }
        });

        program.getDefaults().forEach((field, value) ->
                result.putIfAbsent(field, value));

        program.getCoercions().forEach((field, targetType) -> {
            if (result.containsKey(field)) {
                result.put(field, TypeCoercer.coerce(result.get(field), targetType));
            }
        });

        program.getRemovals().forEach(result::remove);

        return result;
    }
}
