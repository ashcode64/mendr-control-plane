package com.selfhealing.analysis.service.bandit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BanditCategoryTest {

    @Test
    void normalizeAcceptsEnumOnly() {
        assertEquals("DATA_COERCION", BanditCategory.normalize("data_coercion"));
        assertNull(BanditCategory.normalize("MAGIC_FIX"));
        assertNull(BanditCategory.normalize(null));
    }

    @Test
    void missingTagAborts() {
        assertNull(BanditCategory.coerceOrAbort(null, List.of("CORS")));
        assertNull(BanditCategory.coerceOrAbort("  ", List.of("ROUTING")));
    }

    @Test
    void coerceInvalidToSingleAllowedArm() {
        assertEquals("ROUTING", BanditCategory.coerceOrAbort("MAGIC_FIX", List.of("ROUTING")));
        assertNull(BanditCategory.coerceOrAbort("MAGIC_FIX", List.of("ROUTING", "CORS")));
    }

    @Test
    void coerceRejectsValidButNotSampled() {
        assertNull(BanditCategory.coerceOrAbort("CORS", List.of("DATA_COERCION", "ADD_DEFAULT")));
        assertEquals("CORS", BanditCategory.coerceOrAbort("CORS", List.of("CORS", "ROUTING")));
    }
}
