package com.selfhealing.gateway.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.selfhealing.gateway.transform.dsl.MendrProgram;
import com.selfhealing.gateway.transform.dsl.Op;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MendrScriptCompilerTest {

    private MendrScriptCompiler compiler;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        compiler = new MendrScriptCompiler(mapper);
    }

    @Test
    void toSnapshotOps_rawJsonHasExactlyOneOpKeyPerOp() throws Exception {
        MendrProgram program = new MendrProgram(
                MendrProgram.CURRENT_SCHEMA,
                List.of(
                        new Op.Rename("/a", "/b"),
                        new Op.Move("/b", "/c")));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writerFor(new com.fasterxml.jackson.core.type.TypeReference<List<Op>>() {})
                .writeValueAsString(program.ops());

        int opKeys = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"op\"", idx)) >= 0) {
            opKeys++;
            idx += 4;
        }
        assertEquals(2, opKeys, "each op must serialize exactly one 'op' key, got: " + json);
    }

    @Test
    void toSnapshotOps_preservesOpOnNestedConditionalBranches() {
        MendrProgram program = new MendrProgram(
                MendrProgram.CURRENT_SCHEMA,
                List.of(new Op.Conditional(
                        new com.selfhealing.gateway.transform.dsl.Predicate.Exists("/email"),
                        List.of(new Op.Default("/verified", true, Op.Trigger.ABSENT)),
                        List.of(new Op.Default("/verified", false, Op.Trigger.ABSENT)))));

        List<Map<String, Object>> ops = compiler.toSnapshotOps(program);

        assertEquals(1, ops.size());
        assertEquals("conditional", ops.get(0).get("op"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thenBranch =
                (List<Map<String, Object>>) ops.get(0).get("then");
        assertNotNull(thenBranch);
        assertEquals("default", thenBranch.get(0).get("op"));
    }

    @Test
    void toSnapshotOps_preservesOpDiscriminatorOnEveryEntry() {
        MendrProgram program = new MendrProgram(
                MendrProgram.CURRENT_SCHEMA,
                List.of(
                        new Op.Rename("/obj_id/item_id/transmission_id", "/obj_id/item_id/tag_sent"),
                        new Op.Move("/obj_id/item_id/tag_sent", "/tag_sent")
                ));

        List<Map<String, Object>> ops = compiler.toSnapshotOps(program);

        assertEquals(2, ops.size());
        assertEquals("rename", ops.get(0).get("op"));
        assertEquals("/obj_id/item_id/transmission_id", ops.get(0).get("from"));
        assertEquals("/obj_id/item_id/tag_sent", ops.get(0).get("to"));
        assertEquals("move", ops.get(1).get("op"));
        assertEquals("/obj_id/item_id/tag_sent", ops.get(1).get("from"));
        assertEquals("/tag_sent", ops.get(1).get("to"));
    }

    @Test
    void toSnapshotOp_preservesOpDiscriminator() {
        Map<String, Object> snap = compiler.toSnapshotOp(
                new Op.Rename("/userName", "/user_name"));

        assertEquals("rename", snap.get("op"));
        assertEquals("/userName", snap.get("from"));
        assertEquals("/user_name", snap.get("to"));
    }

    @Test
    void roundTrip_parseToSnapshotOps_retainsOpAndRehydratesAst() {
        Map<String, Object> ruleDef = Map.of(
                "schemaVersion", MendrProgram.CURRENT_SCHEMA,
                "ops", List.of(
                        Map.of("op", "rename",
                                "from", "/obj_id/item_id/transmission_id",
                                "to", "/obj_id/item_id/tag_sent"),
                        Map.of("op", "move",
                                "from", "/obj_id/item_id/tag_sent",
                                "to", "/tag_sent")
                ));

        MendrProgram parsed = compiler.parse(ruleDef);
        List<Map<String, Object>> snapshot = compiler.toSnapshotOps(parsed);

        assertEquals(2, snapshot.size());
        for (Map<String, Object> op : snapshot) {
            assertNotNull(op.get("op"), "every snapshot op must carry an 'op' discriminator");
            assertFalse(op.get("op").toString().isBlank());
        }

        MendrProgram roundTripped = compiler.parse(Map.of(
                "schemaVersion", MendrProgram.CURRENT_SCHEMA,
                "ops", snapshot));
        assertEquals(2, roundTripped.ops().size());
        assertInstanceOf(Op.Rename.class, roundTripped.ops().get(0));
        assertInstanceOf(Op.Move.class, roundTripped.ops().get(1));
    }
}
