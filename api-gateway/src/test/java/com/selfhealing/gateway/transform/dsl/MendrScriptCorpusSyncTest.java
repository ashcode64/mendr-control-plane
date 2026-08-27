package com.selfhealing.gateway.transform.dsl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prevents CP/DP MendrScript corpus drift for P0 parity ops.
 */
class MendrScriptCorpusSyncTest {

    @Test
    void controlPlaneAndDataPlaneCorpusAgreeOnP0Cases() throws Exception {
        Path cp = Path.of("src/test/resources/mendrscript/corpus.json").toAbsolutePath().normalize();
        Path dp = Path.of("../mendr-data-plane/infra/nginx/lua/spec/corpus.json");
        if (!Files.exists(dp)) {
            dp = Path.of("../../mendr-data-plane/infra/nginx/lua/spec/corpus.json");
        }
        // Workspace layout: sibling folders under Downloads
        if (!Files.exists(dp)) {
            dp = Path.of(System.getProperty("user.home"), "Downloads", "mendr-data-plane",
                    "infra", "nginx", "lua", "spec", "corpus.json");
        }
        assertTrue(Files.exists(cp), "missing CP corpus: " + cp);
        assertTrue(Files.exists(dp), "missing DP corpus: " + dp
                + " (set sibling mendr-data-plane or adjust path)");

        String cpText = Files.readString(cp);
        String dpText = Files.readString(dp);
        for (String name : new String[]{
                "reformat-date-epoch-s-to-iso8601",
                "reformat-date-iso8601-to-epoch-s",
                "reformat-date-iso8601-to-epoch-ms",
                "scale-kmh-factor",
                "scale-celsius-factor-then-offset",
                "array-remove-then-scale-shifted"
        }) {
            assertTrue(cpText.contains("\"name\": \"" + name + "\"")
                            || cpText.contains("\"name\":\"" + name + "\""),
                    "CP missing case " + name);
            assertTrue(dpText.contains("\"name\": \"" + name + "\"")
                            || dpText.contains("\"name\":\"" + name + "\""),
                    "DP missing case " + name);
        }
    }
}
