package com.selfhealing.analysis.service.skills;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillFingerprintTest {

    @Test
    void fingerprintsAbstractPaths() {
        Map<String, Object> a = Map.of(
                "schemaVersion", "1",
                "ops", List.of(Map.of("op", "coerce", "path", "/user/age", "targetType", "string")));
        Map<String, Object> b = Map.of(
                "schemaVersion", "1",
                "ops", List.of(Map.of("op", "coerce", "path", "/order/qty", "targetType", "string")));
        assertEquals(
                SkillFingerprint.of(a, "TYPE_COERCE"),
                SkillFingerprint.of(b, "TYPE_COERCE"));
    }

    @Test
    void autoDocMentionsOpcodes() {
        Map<String, Object> program = Map.of(
                "ops", List.of(Map.of("op", "rename", "from", "/a", "to", "/b")));
        String doc = SkillFingerprint.autoDoc(program, "FIELD_RENAME", "SCHEMA_MISMATCH", "/a");
        assertTrue(doc.contains("rename"));
        assertTrue(doc.contains("FIELD_RENAME"));
    }

    @Test
    void instantiateRewritesPath() {
        Map<String, Object> program = Map.of(
                "schemaVersion", "1",
                "ops", List.of(Map.of("op", "coerce", "path", "/old", "targetType", "integer")));
        Map<String, Object> out = SkillFingerprint.instantiate(program, "/new");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) out.get("ops");
        assertEquals("/new", ops.get(0).get("path"));
    }

    @Test
    void sketchCompatibleRequiresOpcodeOverlap() {
        Map<String, Object> match = SkillFingerprint.sketchMatchPayload(
                Map.of("ops", List.of(Map.of("op", "coerce", "path", "/x", "targetType", "string"))),
                "TYPE_COERCE");
        assertTrue(SkillFingerprint.sketchCompatible(match, "TYPE_COERCE", List.of("coerce", "map_value")));
        assertFalse(SkillFingerprint.sketchCompatible(match, "TYPE_COERCE", List.of("rename")));
        assertFalse(SkillFingerprint.sketchCompatible(match, "FIELD_RENAME", List.of("coerce")));
    }
}
