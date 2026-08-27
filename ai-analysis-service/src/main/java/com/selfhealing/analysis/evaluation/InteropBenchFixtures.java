package com.selfhealing.analysis.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * InteropBench fixture loader. Fixtures live in {@code classpath:interop/fixtures.json}.
 */
public final class InteropBenchFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InteropBenchFixtures() {}

    public record Fixture(
            String id,
            String clazz,
            String llmDifficulty,
            String planClass,
            Map<String, Object> sourceSchema,
            Map<String, Object> targetSchema,
            Map<String, Object> input,
            Map<String, Object> goldenOutput,
            List<String> expectedMismatches,
            Map<String, Object> expectedProgram,
            boolean assertNoP0Detector
    ) {}

    @SuppressWarnings("unchecked")
    public static List<Fixture> loadAll() {
        try (InputStream in = InteropBenchFixtures.class.getClassLoader()
                .getResourceAsStream("interop/fixtures.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource interop/fixtures.json");
            }
            JsonNode root = MAPPER.readTree(in);
            List<Fixture> out = new ArrayList<>();
            for (JsonNode n : root) {
                out.add(parse(n));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load InteropBench fixtures", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Fixture parse(JsonNode n) throws Exception {
        Map<String, Object> map = MAPPER.convertValue(n, Map.class);
        List<String> mismatches = new ArrayList<>();
        Object em = map.get("expected_mismatches");
        if (em instanceof List<?> list) {
            for (Object o : list) mismatches.add(String.valueOf(o));
        }
        Map<String, Object> prog = null;
        if (map.get("expected_program") instanceof Map<?, ?> m) {
            prog = new LinkedHashMap<>((Map<String, Object>) m);
        }
        String plan = map.get("plan_class") == null ? null : String.valueOf(map.get("plan_class"));
        if ("null".equals(plan)) plan = null;
        return new Fixture(
                String.valueOf(map.get("id")),
                String.valueOf(map.get("class")),
                String.valueOf(map.getOrDefault("llm_difficulty", "easy")),
                plan,
                asMap(map.get("source_schema")),
                asMap(map.get("target_schema")),
                asMap(map.get("input")),
                asMap(map.get("golden_output")),
                mismatches,
                prog,
                Boolean.TRUE.equals(map.get("assert_no_p0_detector"))
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }
}
