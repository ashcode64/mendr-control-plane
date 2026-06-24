package com.selfhealing.gateway.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaInferrerTest {

    @Test
    void infersRequiredAndOptionalFromMultipleExamples() {
        Map<String, Object> e1 = new LinkedHashMap<>();
        e1.put("customerId", "C1");
        e1.put("amount", 10.0);
        e1.put("note", "first");

        Map<String, Object> e2 = new LinkedHashMap<>();
        e2.put("customerId", "C2");
        e2.put("amount", 20.0);
        // no "note" → note is optional

        Map<String, Object> schema = JsonSchemaInferrer.infer(List.of(e1, e2));

        assertThat(schema).isNotNull();
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactlyInAnyOrder("customerId", "amount");
        assertThat(required).doesNotContain("note");

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(((Map<?, ?>) props.get("amount")).get("type")).isEqualTo("number");
        assertThat(((Map<?, ?>) props.get("customerId")).get("type")).isEqualTo("string");
    }

    @Test
    void singleExampleMakesEveryFieldRequired() {
        Map<String, Object> schema = JsonSchemaInferrer.infer(List.of(Map.of("a", 1, "b", "x")));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void infersEnumForLowCardinalityStrings() {
        Map<String, Object> schema = JsonSchemaInferrer.infer(List.of(
                Map.of("status", "PAID"),
                Map.of("status", "PENDING"),
                Map.of("status", "PAID")));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) props.get("status");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) status.get("enum");
        assertThat(enumValues).containsExactlyInAnyOrder("PAID", "PENDING");
    }

    @Test
    void infersNestedArrayItemShape() {
        Map<String, Object> example = Map.of(
                "items", List.of(Map.of("menuItemId", 1, "quantity", 2)));
        Map<String, Object> schema = JsonSchemaInferrer.infer(List.of(example));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) props.get("items");
        assertThat(items.get("type")).isEqualTo("array");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemSchema = (Map<String, Object>) items.get("items");
        assertThat(itemSchema.get("type")).isEqualTo("object");
    }

    @Test
    void emptyInputReturnsNull() {
        assertThat(JsonSchemaInferrer.infer(List.of())).isNull();
        assertThat(JsonSchemaInferrer.infer(null)).isNull();
    }
}
