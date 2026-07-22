package com.selfhealing.analysis.service.gepa;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GepaCompileServiceTest {

    @Test
    void miproFallbackDistillsCriticTips() {
        List<Map<String, Object>> dataset = List.of(
                Map.of(
                        "critic_text", "Do not confuse rename with coerce on this field",
                        "change_type", "TYPE_COERCE",
                        "json_path", "/userId"),
                Map.of(
                        "critic_text", "Authorization header must stay protected",
                        "change_type", "FIELD_RENAME"),
                Map.of("change_type", "ADD_DEFAULT")
        );

        GepaCompileService.CompileResult result = GepaCompileService.miproFallback(dataset);
        assertEquals("mipro_fallback", result.compiler());
        assertTrue(result.promptText().contains("FIELD_RENAME"));
        assertTrue(result.promptText().contains("protected"));
        assertTrue(result.promptText().contains("ADD_DEFAULT")
                || result.promptText().contains("change_type=ADD_DEFAULT"));
        assertEquals(3, ((Number) result.metrics().get("examples")).intValue());
    }

    @Test
    void tipFromCriticHandlesRenameCoerce() {
        String tip = GepaCompileService.tipFromCritic(
                "rename vs coerce confusion", "TYPE_COERCE", "/age");
        assertTrue(tip.contains("FIELD_RENAME"));
        assertTrue(tip.contains("/age"));
    }
}
