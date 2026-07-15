package com.selfhealing.gateway.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedSurfaceCompilerTest {

    @Test
    void compilesBodyPointersAndQueryParams() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "amount", Map.of("type", "number"),
                        "meta", Map.of(
                                "type", "object",
                                "properties", Map.of("tag", Map.of("type", "string")))));

        Map<String, Object> surface = AllowedSurfaceCompiler.compile(
                schema, List.of("page", "size"), "OPENAPI_DECLARED", 0.9);

        assertThat(surface.get("schemaSource")).isEqualTo("OPENAPI_DECLARED");
        assertThat(surface.get("specTrust")).isEqualTo(0.9);
        @SuppressWarnings("unchecked")
        List<String> pointers = (List<String>) surface.get("bodyPointers");
        assertThat(pointers).contains("/amount", "/meta", "/meta/tag");
        @SuppressWarnings("unchecked")
        List<String> qps = (List<String>) surface.get("queryParams");
        assertThat(qps).containsExactly("page", "size");
        assertThat(surface.get("additionalProperties")).isEqualTo(false);
    }
}
