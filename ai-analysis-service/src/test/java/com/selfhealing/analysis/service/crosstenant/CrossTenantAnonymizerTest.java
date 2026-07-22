package com.selfhealing.analysis.service.crosstenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossTenantAnonymizerTest {

    private final CrossTenantAnonymizer anonymizer =
            new CrossTenantAnonymizer(new ObjectMapper());

    @Test
    void hashesTenantWithoutRawUuid() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String hash = anonymizer.hashTenant(id);
        assertFalse(hash.contains(id.toString()));
        assertEquals(64, hash.length());
    }

    @Test
    void scrubSkillStripsValuesAndPii() {
        Map<String, Object> raw = Map.of(
                "skillKey", "k1",
                "autodoc", "Contact admin@example.com for coerce",
                "changeType", "TYPE_COERCE",
                "category", "SCHEMA_MISMATCH",
                "program", Map.of(
                        "schemaVersion", "1",
                        "ops", List.of(Map.of(
                                "op", "coerce",
                                "path", "/age",
                                "value", "secret-live-value",
                                "targetType", "string"))));
        Map<String, Object> scrubbed = anonymizer.scrubSkillPayload(raw);
        assertTrue(String.valueOf(scrubbed.get("autodoc")).contains("[EMAIL]"));
        @SuppressWarnings("unchecked")
        Map<String, Object> program = (Map<String, Object>) scrubbed.get("program");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) program.get("ops");
        assertFalse(ops.get(0).containsKey("value"));
    }

    @Test
    void generalizeScopeHidesEndpointIds() {
        String scope = CrossTenantAnonymizer.generalizeScope(
                "svcA/svcB/users/550e8400-e29b-41d4-a716-446655440000");
        assertFalse(scope.contains("550e8400"));
        assertTrue(scope.contains("/*"));
    }
}

class CrossTenantGateDefaultOffTest {

    @Test
    void defaultDisabledBlocksPublishAndImport() {
        CrossTenantGate gate = new CrossTenantGate(null);
        ReflectionTestUtils.setField(gate, "enabled", false);
        ReflectionTestUtils.setField(gate, "requirePrivacyReview", true);
        UUID tenant = UUID.randomUUID();
        assertFalse(gate.globallyEnabled());
        assertFalse(gate.canPublish(tenant));
        assertFalse(gate.canImport(tenant));
    }
}
