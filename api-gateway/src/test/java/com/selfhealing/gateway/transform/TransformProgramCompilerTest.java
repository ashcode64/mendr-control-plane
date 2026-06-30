package com.selfhealing.gateway.transform;

import com.selfhealing.gateway.model.ResponseTransformationRule;
import com.selfhealing.gateway.model.TransformationRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformProgramCompilerTest {

    private final TransformProgramCompiler compiler = new TransformProgramCompiler();

    private TransformationRule rename(String from, String to) {
        return TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.FIELD_RENAME)
                .ruleDefinition(Map.of("mappings", Map.of(from, to)))
                .build();
    }

    @Test
    void dslProgramCompileRequest_preservesOpInOpsBucket() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.DSL_PROGRAM)
                .ruleDefinition(Map.of(
                        "schemaVersion", "mendrscript/v1",
                        "ops", List.of(
                                Map.of("op", "rename",
                                        "from", "/obj_id/item_id/transmission_id",
                                        "to", "/obj_id/item_id/tag_sent"),
                                Map.of("op", "move",
                                        "from", "/obj_id/item_id/tag_sent",
                                        "to", "/tag_sent"))))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.getSchemaVersion()).isEqualTo("v2");
        assertThat(program.getOps()).hasSize(2);
        assertThat(program.getOps().get(0)).containsEntry("op", "rename");
        assertThat(program.getOps().get(1)).containsEntry("op", "move");
    }

    @Test
    void conflictingRenamesOfSameSourceThrow() {
        // two approved rules disagree on what 'amount' becomes — must not silently
        // last-write-wins (plan §4.10)
        TransformationRule a = rename("amount", "amount_cents");
        TransformationRule b = rename("amount", "total");

        assertThatThrownBy(() -> compiler.compileRequest(List.of(a, b)))
                .isInstanceOf(TransformProgramConflictException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void identicalRenamesFromTwoRulesDoNotConflict() {
        // same field, same target across two rules is idempotent, not a conflict
        TransformProgram program = compiler.compileRequest(List.of(rename("a", "b"), rename("a", "b")));
        assertThat(program.getRenames()).containsEntry("a", "b");
    }

    @Test
    void fieldBothRemovedAndCoercedThrows() {
        TransformationRule coerce = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.TYPE_COERCE)
                .ruleDefinition(Map.of("coercions", Map.of("status", "integer")))
                .build();
        TransformationRule remove = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.FIELD_RENAME) // type irrelevant; def carries fields
                .ruleDefinition(Map.of("fields", List.of("status")))
                .build();

        assertThatThrownBy(() -> compiler.compileRequest(List.of(coerce, remove)))
                .isInstanceOf(TransformProgramConflictException.class)
                .hasMessageContaining("status");
    }

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
    void scaleRuleCompilesIntoScalesBucket() {
        TransformationRule scale = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.SCALE)
                .ruleDefinition(Map.of("scales", List.of(Map.of(
                        "path", "/amount", "numerator", 1, "denominator", 100,
                        "expectedMin", 0, "expectedMax", 1_000_000))))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(scale));

        assertThat(program.getScales()).hasSize(1);
        assertThat(program.getScales().get(0)).containsEntry("path", "/amount");
        assertThat(program.isEmpty()).isFalse();
    }

    @Test
    void conflictingScaleFactorsThrow() {
        TransformationRule a = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.SCALE)
                .ruleDefinition(Map.of("scales", List.of(Map.of(
                        "path", "/amount", "numerator", 1, "denominator", 100))))
                .build();
        TransformationRule b = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.SCALE)
                .ruleDefinition(Map.of("scales", List.of(Map.of(
                        "path", "/amount", "numerator", 1, "denominator", 1000))))
                .build();

        assertThatThrownBy(() -> compiler.compileRequest(List.of(a, b)))
                .isInstanceOf(TransformProgramConflictException.class)
                .hasMessageContaining("/amount");
    }

    @Test
    void coalesceRuleCompilesIntoCoalesceBucket() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.COALESCE)
                .ruleDefinition(Map.of("coalesce", List.of(Map.of("path", "/status", "value", "UNKNOWN"))))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.getCoalesce()).hasSize(1);
        assertThat(program.getCoalesce().get(0)).containsEntry("path", "/status");
        assertThat(program.getCoalesce().get(0)).containsEntry("value", "UNKNOWN");
        assertThat(program.isEmpty()).isFalse();
        // value-mutating: must not ride the flat streaming path
        assertThat(program.isStreamable()).isFalse();
    }

    @Test
    void conflictingCoalesceValuesThrow() {
        TransformationRule a = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.COALESCE)
                .ruleDefinition(Map.of("coalesce", List.of(Map.of("path", "/status", "value", "A"))))
                .build();
        TransformationRule b = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.COALESCE)
                .ruleDefinition(Map.of("coalesce", List.of(Map.of("path", "/status", "value", "B"))))
                .build();

        assertThatThrownBy(() -> compiler.compileRequest(List.of(a, b)))
                .isInstanceOf(TransformProgramConflictException.class)
                .hasMessageContaining("/status");
    }

    @Test
    void mapValueRuleCompilesAndIsNonStreamable() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.MAP_VALUE)
                .ruleDefinition(Map.of("valueMaps", List.of(Map.of(
                        "path", "/status",
                        "mapping", Map.of("A", "ACTIVE"),
                        "onUnmapped", "reject"))))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.getValueMaps()).hasSize(1);
        assertThat(program.getValueMaps().get(0)).containsEntry("path", "/status");
        assertThat(program.isStreamable()).isFalse();
    }

    @Test
    void conflictingValueMapsThrow() {
        TransformationRule a = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.MAP_VALUE)
                .ruleDefinition(Map.of("valueMaps", List.of(Map.of(
                        "path", "/status", "mapping", Map.of("A", "ACTIVE")))))
                .build();
        TransformationRule b = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.MAP_VALUE)
                .ruleDefinition(Map.of("valueMaps", List.of(Map.of(
                        "path", "/status", "mapping", Map.of("A", "ENABLED")))))
                .build();

        assertThatThrownBy(() -> compiler.compileRequest(List.of(a, b)))
                .isInstanceOf(TransformProgramConflictException.class)
                .hasMessageContaining("/status");
    }

    @Test
    void reformatDateRuleCompilesIntoDateFormatsBucket() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.REFORMAT_DATE)
                .ruleDefinition(Map.of("dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "epoch_s", "targetFormat", "iso8601"))))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.getDateFormats()).hasSize(1);
        assertThat(program.getDateFormats().get(0)).containsEntry("targetFormat", "iso8601");
    }

    @Test
    void reformatDateCarriesAssumeTimezoneIntoBucket() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.REFORMAT_DATE)
                .ruleDefinition(Map.of("dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "datetime", "targetFormat", "iso8601",
                        "assumeTimezone", "+05:30"))))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.getDateFormats()).hasSize(1);
        assertThat(program.getDateFormats().get(0)).containsEntry("assumeTimezone", "+05:30");
    }

    @Test
    void conflictingDateConversionsThrow() {
        TransformationRule a = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.REFORMAT_DATE)
                .ruleDefinition(Map.of("dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "epoch_s", "targetFormat", "iso8601"))))
                .build();
        TransformationRule b = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.REFORMAT_DATE)
                .ruleDefinition(Map.of("dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "epoch_ms", "targetFormat", "iso8601"))))
                .build();

        assertThatThrownBy(() -> compiler.compileRequest(List.of(a, b)))
                .isInstanceOf(TransformProgramConflictException.class)
                .hasMessageContaining("/created");
    }

    @Test
    void stripUnknownRuleCompilesIntoBucket() {
        TransformationRule rule = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.STRIP_UNKNOWN)
                .ruleDefinition(Map.of("stripUnknown", List.of(Map.of(
                        "path", "/", "allowed", List.of("id", "name")))))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(rule));

        assertThat(program.getStripUnknown()).hasSize(1);
        assertThat(program.isStreamable()).isFalse();
    }

    @Test
    void wrapAndUnwrapArraySamePathConflicts() {
        TransformationRule wrap = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.WRAP_ARRAY)
                .ruleDefinition(Map.of("wrapArrays", List.of(Map.of("path", "/items"))))
                .build();
        TransformationRule unwrap = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.UNWRAP_ARRAY)
                .ruleDefinition(Map.of("unwrapArrays", List.of(Map.of("path", "/items"))))
                .build();

        assertThatThrownBy(() -> compiler.compileRequest(List.of(wrap, unwrap)))
                .isInstanceOf(TransformProgramConflictException.class)
                .hasMessageContaining("/items");
    }

    @Test
    void wrapArrayRuleCompilesIntoBucket() {
        TransformationRule wrap = TransformationRule.builder()
                .ruleType(TransformationRule.RuleType.WRAP_ARRAY)
                .ruleDefinition(Map.of("wrapArrays", List.of(Map.of("path", "/item"))))
                .build();

        TransformProgram program = compiler.compileRequest(List.of(wrap));

        assertThat(program.getWrapArrays()).hasSize(1);
        assertThat(program.getWrapArrays().get(0)).containsEntry("path", "/item");
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
