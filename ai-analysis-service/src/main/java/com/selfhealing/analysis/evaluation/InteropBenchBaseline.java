package com.selfhealing.analysis.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.registry.UnitDateDetector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Freeze writers. Tests must NOT call these against {@code src/test/resources}
 * unless {@code -Dmendr.interop.freeze=true} (explicit D2 re-baseline).
 */
public final class InteropBenchBaseline {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InteropBenchBaseline() {}

    public static boolean freezeWritesEnabled() {
        return Boolean.parseBoolean(System.getProperty("mendr.interop.freeze", "false"));
    }

    public static Map<String, Object> freeze(Path outFile) throws Exception {
        return writeModeA(outFile, InteropBenchModeA.run(InteropBenchFixtures.loadAll()), "post-detector");
    }

    public static Map<String, Object> freezeWithoutDetectors(Path outFile) throws Exception {
        UnitDateDetector.DetectorConfig off =
                new UnitDateDetector.DetectorConfig(false, false, Set.of());
        return writeModeA(outFile, InteropBenchModeA.run(InteropBenchFixtures.loadAll(), off), "pre-detector");
    }

    private static Map<String, Object> writeModeA(Path outFile, InteropBenchModeA.Report report, String label)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", label);
        payload.put("frozenAt", java.time.Instant.now().toString());
        payload.put("immutable", true);
        payload.put("rewriteOnTest", false);
        payload.put("sequencing", "pre-detector is detectors-off surrogate if chronological freeze was missed");
        payload.put("summary", InteropBenchModeA.toMarkdownTable(report));
        payload.put("cases", report.cases());
        writeIfAllowed(outFile, payload);
        return payload;
    }

    static void writeIfAllowed(Path outFile, Object payload) throws Exception {
        if (outFile == null) return;
        boolean resources = outFile.toString().replace('\\', '/').contains("src/test/resources");
        if (resources && !freezeWritesEnabled()) {
            throw new IllegalStateException(
                    "Refusing to rewrite committed freeze " + outFile
                            + " (set -Dmendr.interop.freeze=true to re-baseline)");
        }
        Files.createDirectories(outFile.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outFile.toFile(), payload);
    }
}
