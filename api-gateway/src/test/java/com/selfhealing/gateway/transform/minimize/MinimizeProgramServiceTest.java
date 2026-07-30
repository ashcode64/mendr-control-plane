package com.selfhealing.gateway.transform.minimize;

import com.selfhealing.gateway.transform.dsl.MendrProgram;
import com.selfhealing.gateway.transform.dsl.Op;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimizeProgramServiceTest {

    @Test
    void isBetterPrefersFewerOps() {
        MendrProgram draft = new MendrProgram("mendrscript/v1", List.of(
                new Op.Rename("/a", "/b"),
                new Op.Rename("/c", "/d")));
        MendrProgram cand = new MendrProgram("mendrscript/v1", List.of(
                new Op.Rename("/a", "/b")));
        assertTrue(MinimizeProgramService.isBetter(cand, draft));
    }

    @Test
    void isBetterPrefersFewerValueMutatingAtSameSize() {
        MendrProgram draft = new MendrProgram("mendrscript/v1", List.of(
                new Op.Rename("/a", "/b"),
                new Op.Coerce("/x", "integer")));
        MendrProgram cand = new MendrProgram("mendrscript/v1", List.of(
                new Op.Rename("/a", "/b"),
                new Op.Remove("/y")));
        assertTrue(MinimizeProgramService.isBetter(cand, draft));
        assertFalse(MinimizeProgramService.isBetter(draft, cand));
    }

    @Test
    void minimizeRequestRecordHoldsFields() {
        var req = new MinimizeProgramService.MinimizeRequest(null, null, null, 0.9, null, null, null);
        assertTrue(req.specTrust() != null && req.specTrust() > 0.8);
    }

    @Test
    void isBetterRejectsEmptyOverNonEmpty() {
        MendrProgram draft = new MendrProgram("mendrscript/v1", List.of(
                new Op.Rename("/a", "/b")));
        MendrProgram empty = new MendrProgram("mendrscript/v1", List.of());
        assertFalse(MinimizeProgramService.isBetter(empty, draft));
    }
}
