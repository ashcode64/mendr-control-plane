package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiToolAdapterTest {

    @Test
    void convertsAnthropicToolToFunctionDeclaration() {
        Map<String, Object> anthropic = Map.of(
                "name", "propose_field_rename",
                "description", "Rename fields",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of("mappings", Map.of("type", "object")),
                        "required", List.of("mappings")));

        Map<String, Object> decl = GeminiToolAdapter.toFunctionDeclaration(anthropic);

        assertThat(decl.get("name")).isEqualTo("propose_field_rename");
        assertThat(decl.get("description")).isEqualTo("Rename fields");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) decl.get("parameters");
        assertThat(params.get("type")).isEqualTo("object");
    }

    @Test
    void forcedToolConfigIncludesAllowedFunctionNames() {
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) GeminiToolAdapter.toolConfigForced("propose_routing_override")
                .get("functionCallingConfig");
        assertThat(cfg.get("mode")).isEqualTo("ANY");
        assertThat(cfg.get("allowedFunctionNames")).isEqualTo(List.of("propose_routing_override"));
    }
}
