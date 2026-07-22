package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DriftedFieldsAssemblerTest {

    @Test
    void emitsAllSchemaFieldsNotJustTopOne() {
        SchemaDiffResult diff = new SchemaDiffResult(
                SchemaDiffResult.Kind.TYPE_MISMATCH,
                "multi",
                Set.of("missingA"),
                Map.of("oldName", "newName"),
                Map.of("age", "integer"),
                Map.of("missingA", 0),
                List.of(),
                true);

        List<Map<String, Object>> fields = DriftedFieldsAssembler.fromSchemaDiff(diff);
        assertTrue(fields.size() >= 3);
        assertTrue(fields.stream().anyMatch(f -> "/age".equals(f.get("json_path"))));
        assertTrue(fields.stream().anyMatch(f -> "/missingA".equals(f.get("json_path"))));
        assertTrue(fields.stream().anyMatch(f -> "/newName".equals(f.get("json_path"))));
        assertTrue(fields.stream().allMatch(f -> f.get("minimal_op") instanceof Map));
    }

    @Test
    void emptyDiffReturnsEmpty() {
        assertTrue(DriftedFieldsAssembler.fromSchemaDiff(SchemaDiffResult.empty()).isEmpty());
    }
}
