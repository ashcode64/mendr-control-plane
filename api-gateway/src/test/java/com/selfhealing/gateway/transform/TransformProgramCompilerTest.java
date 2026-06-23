package com.selfhealing.gateway.transform;

import com.selfhealing.gateway.model.ResponseTransformationRule;
import com.selfhealing.gateway.model.TransformationRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransformProgramCompilerTest {

    private final TransformProgramCompiler compiler = new TransformProgramCompiler();

    @Test
    void emptyRulesProduceEmptyStreamableProgram() {
        TransformProgram program = compiler.compileRequest(List.of());
        assertThat(program.isEmpty()).isTrue();
        assertThat(program.isStreamable()).isTrue();
    }

    @Test
    void flatRenameRuleIsStreamable() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.FIELD_RENAME)
                .ruleDefinition(Map.of("mappings", Map.of("oldAmount", "amount")))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.isStreamable()).isTrue();
        assertThat(program.getRenames()).containsEntry("oldAmount", "amount");
    }

    @Test
    void nestedTransformMarksProgramNonStreamable() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.NESTED_TRANSFORM)
                .ruleDefinition(Map.of("mappings", Map.of("a", "b")))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.isStreamable()).isFalse();
    }

    @Test
    void responseWrapMarksProgramNonStreamable() {
        ResponseTransformationRule rule = ResponseTransformationRule.builder()
                .ruleType(ResponseTransformationRule.ResponseRuleType.RESPONSE_WRAP)
                .ruleDefinition(Map.of("key", "data"))
                .build();

        TransformProgram program = compiler.compileResponse(List.of(rule));

        assertThat(program.isStreamable()).isFalse();
        assertThat(program.getWrapKey()).isEqualTo("data");
    }

    @Test
    void responseUnwrapMarksProgramNonStreamable() {
        ResponseTransformationRule rule = ResponseTransformationRule.builder()
                .ruleType(ResponseTransformationRule.ResponseRuleType.RESPONSE_UNWRAP)
                .ruleDefinition(Map.of("key", "payload"))
                .build();

        TransformProgram program = compiler.compileResponse(List.of(rule));

        assertThat(program.isStreamable()).isFalse();
        assertThat(program.getUnwrapKey()).isEqualTo("payload");
    }

    @Test
    void renameTargetCollidingWithDefaultMarksNonStreamable() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.FIELD_RENAME)
                .ruleDefinition(Map.of(
                        "mappings", Map.of("wrongKey", "amount"),
                        "defaults", Map.of("amount", 0)))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.isStreamable()).isFalse();
    }

    @Test
    void duplicateRenameTargetsMarkNonStreamable() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.FIELD_RENAME)
                .ruleDefinition(Map.of("mappings", Map.of("a", "target", "b", "target")))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.isStreamable()).isFalse();
    }

    @Test
    void mergesMultipleFlatResponseRules() {
        List<ResponseTransformationRule> rules = List.of(
                ResponseTransformationRule.builder()
                        .ruleType(ResponseTransformationRule.ResponseRuleType.RESPONSE_FIELD_RENAME)
                        .ruleDefinition(Map.of("mappings", Map.of("txnId", "transactionId")))
                        .build(),
                ResponseTransformationRule.builder()
                        .ruleType(ResponseTransformationRule.ResponseRuleType.RESPONSE_ADD_DEFAULT)
                        .ruleDefinition(Map.of("defaults", Map.of("status", "OK")))
                        .build());

        TransformProgram program = compiler.compileResponse(rules);

        assertThat(program.isStreamable()).isTrue();
        assertThat(program.getRenames()).containsEntry("txnId", "transactionId");
        assertThat(program.getDefaults()).containsEntry("status", "OK");
    }
}
