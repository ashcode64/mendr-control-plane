package com.selfhealing.gateway.transform.dsl;

import com.selfhealing.gateway.transform.MendrScriptCompiler;
import com.selfhealing.gateway.transform.TransformProgram;
import com.selfhealing.gateway.transform.TransformProgramCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanClassClassifierTest {

    private final MendrScriptCompiler compiler = new MendrScriptCompiler(
            new com.fasterxml.jackson.databind.ObjectMapper());
    private final TransformProgramCompiler programCompiler = new TransformProgramCompiler();

    @Test
    void emptyProgramIsPassthrough() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA, List.of()));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.PASSTHROUGH);
    }

    @Test
    void wrapIsForwardOnlyNotPrefilterable() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Wrap("data"))));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.FORWARD_ONLY);
        assertThat(c.prefilterable()).isFalse();
    }

    @Test
    void unwrapIsForwardOnly() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Unwrap("data"))));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.FORWARD_ONLY);
    }

    @Test
    void presentKeyRenameIsPrefilterable() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Rename("/amt", "/amount"))));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.PREFILTERABLE);
        assertThat(c.prefilterLiterals()).contains("amt", "amount");
        assertThat(c.writePointers()).contains("/amt", "/amount");
    }

    @Test
    void defaultAbsentIsForwardOnly() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Default("/active", true, Op.Trigger.ABSENT))));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.FORWARD_ONLY);
        assertThat(c.prefilterable()).isFalse();
    }

    @Test
    void moveIsUnbounded() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Move("/a/b", "/c"))));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.UNBOUNDED);
        assertThat(c.maxWindowDepth()).isEqualTo(PlanClassClassifier.UNBOUNDED);
    }

    @Test
    void renameOnlyConditionalIsUnionOfBranches() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Conditional(
                        new Predicate.Exists("/email"),
                        List.of(new Op.Rename("/a", "/b")),
                        List.of()))));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.PREFILTERABLE);
        assertThat(c.prefilterLiterals()).contains("email", "a", "b");
    }

    @Test
    void conditionalWithCrossParentMoveIsUnbounded() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Conditional(
                        new Predicate.Exists("/email"),
                        List.of(new Op.Move("/a/b", "/c")),
                        List.of()))));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.UNBOUNDED);
    }

    @Test
    void dslCompilePopulatesPlanClassAndSetsStreamableFromPlanClass() {
        var rule = com.selfhealing.gateway.model.TransformationRule.builder()
                .ruleType(com.selfhealing.gateway.model.TransformationRule.RuleType.DSL_PROGRAM)
                .ruleDefinition(Map.of(
                        "schemaVersion", "mendrscript/v1",
                        "ops", List.of(Map.of("op", "rename", "from", "/amt", "to", "/amount"))))
                .build();
        TransformProgram program = programCompiler.compileRequest(List.of(rule));
        assertThat(program.isStreamable()).isTrue();
        assertThat(program.getPlanClass()).isEqualTo(PlanClassClassifier.PREFILTERABLE);
        assertThat(program.getPrefilterLiterals()).contains("amt");
    }

    @Test
    void sameParentMoveIsBoundedWindow() {
        var c = compiler.classify(new MendrProgram(MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Move("/user/amt", "/user/amount"))));
        assertThat(c.planClass()).isEqualTo(PlanClassClassifier.BOUNDED_WINDOW);
        assertThat(c.prefilterable()).isFalse();
    }

    @Test
    void wrapDslIsForwardOnly() {
        var rule = com.selfhealing.gateway.model.TransformationRule.builder()
                .ruleType(com.selfhealing.gateway.model.TransformationRule.RuleType.DSL_PROGRAM)
                .ruleDefinition(Map.of(
                        "schemaVersion", "mendrscript/v1",
                        "ops", List.of(Map.of("op", "wrap", "key", "data"))))
                .build();
        TransformProgram program = programCompiler.compileRequest(List.of(rule));
        assertThat(program.getPlanClass()).isEqualTo(PlanClassClassifier.FORWARD_ONLY);
        assertThat(program.isPrefilterable()).isFalse();
    }

    @Test
    void snapshotJsonCarriesPlanClassWireShape() throws Exception {
        var rule = com.selfhealing.gateway.model.TransformationRule.builder()
                .ruleType(com.selfhealing.gateway.model.TransformationRule.RuleType.DSL_PROGRAM)
                .ruleDefinition(Map.of(
                        "schemaVersion", "mendrscript/v1",
                        "ops", List.of(Map.of("op", "rename", "from", "/amt", "to", "/amount"))))
                .build();
        TransformProgram program = programCompiler.compileRequest(List.of(rule));
        var snap = com.selfhealing.gateway.dto.RouteConfigSnapshot.TransformProgramSnapshot.builder()
                .empty(program.isEmpty())
                .streamable(program.isStreamable())
                .schemaVersion(program.getSchemaVersion())
                .ops(program.getOps())
                .planClass(program.getPlanClass())
                .prefilterLiterals(program.getPrefilterLiterals())
                .writePointers(program.getWritePointers())
                .maxWindowDepth(program.getMaxWindowDepth())
                .prefilterable(program.isPrefilterable())
                .build();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var n = mapper.valueToTree(snap);
        assertThat(n.get("planClass").asText()).isEqualTo(PlanClassClassifier.PREFILTERABLE);
        assertThat(n.get("prefilterLiterals").isArray()).isTrue();
        assertThat(n.get("writePointers").isArray()).isTrue();
        assertThat(n.has("lengthPreserving")).isFalse();
        assertThat(n.get("streamable").asBoolean()).isTrue();
        assertThat(n.get("ops").get(0).get("op").asText()).isEqualTo("rename");
    }
}
