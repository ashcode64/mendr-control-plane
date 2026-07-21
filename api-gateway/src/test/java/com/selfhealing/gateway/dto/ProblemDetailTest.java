package com.selfhealing.gateway.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailTest {

    @Test
    void roundTripPreservesExtensions() {
        ProblemDetail pd = ProblemDetail.builder()
                .type("https://mendr.dev/problems/type_coerce")
                .title("TYPE_COERCE")
                .status(400)
                .detail("amount must be integer")
                .instance("/api/pay")
                .extensions(Map.of(
                        "template_id", "jackson_deserialize_type_mismatch",
                        "json_path", "/amount",
                        "spec_trust", 0.5))
                .build();

        Map<String, Object> map = pd.toMap();
        ProblemDetail back = ProblemDetail.fromMap(map);
        assertThat(back.getType()).isEqualTo(pd.getType());
        assertThat(back.getDetail()).isEqualTo(pd.getDetail());
        assertThat(back.getExtensions()).containsEntry("json_path", "/amount");
        assertThat(back.getExtensions()).containsEntry("template_id", "jackson_deserialize_type_mismatch");
    }

    @Test
    void fromMapLiftsNonStandardKeysIntoExtensions() {
        ProblemDetail pd = ProblemDetail.fromMap(Map.of(
                "type", "https://example/problem",
                "title", "Bad",
                "status", 422,
                "detail", "nope",
                "json_path", "/x",
                "owner_action_required", true));
        assertThat(pd.getExtensions()).containsEntry("json_path", "/x");
        assertThat(pd.getExtensions()).containsEntry("owner_action_required", true);
    }
}
