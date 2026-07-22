package com.selfhealing.analysis.service.metamemory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetaMemoryServiceTest {

    @Test
    void pathPrefixDropsLeaf() {
        assertEquals("/user/profile", MetaMemoryService.pathPrefix("/user/profile/age"));
        assertEquals("/user", MetaMemoryService.pathPrefix("/user/age"));
        assertEquals("/", MetaMemoryService.pathPrefix("/age"));
        assertEquals("/", MetaMemoryService.pathPrefix(null));
    }

    @Test
    void clusterKeyStable() {
        String a = MetaMemoryService.clusterKey(Map.of(
                "tenant_id", "11111111-1111-1111-1111-111111111111",
                "change_type", "TYPE_COERCE",
                "category", "SCHEMA_MISMATCH",
                "json_path", "/user/age"));
        String b = MetaMemoryService.clusterKey(Map.of(
                "tenant_id", "11111111-1111-1111-1111-111111111111",
                "change_type", "type_coerce",
                "category", "schema_mismatch",
                "json_path", "/user/name"));
        assertEquals(a, b);
    }

    @Test
    void synthesizeRuleIncludesCounts() {
        String rule = MetaMemoryService.synthesizeRule("TYPE_COERCE", "SCHEMA_MISMATCH", "/user", 5);
        assertTrue(rule.contains("n=5"));
        assertTrue(rule.contains("TYPE_COERCE"));
    }
}
