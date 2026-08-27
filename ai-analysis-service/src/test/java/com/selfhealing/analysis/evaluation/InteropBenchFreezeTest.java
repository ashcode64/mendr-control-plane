package com.selfhealing.analysis.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

/**
 * Explicit D2 re-baseline. Run:
 * {@code mvn -Dmendr.interop.freeze=true -Dtest=InteropBenchFreezeTest test}
 */
class InteropBenchFreezeTest {

    @Test
    @EnabledIfSystemProperty(named = "mendr.interop.freeze", matches = "true")
    void writeCommittedFreezes() throws Exception {
        Path res = Path.of("src", "test", "resources", "interop", "baselines");
        InteropBenchBaseline.freezeWithoutDetectors(res.resolve("mode-a-pre-detector.json"));
        InteropBenchBaseline.freeze(res.resolve("mode-a-post-detector.json"));
        InteropBenchModeB.runThroughputPilot(res.resolve("throughput-pilot.json"));
        InteropBenchModeB.Report b = InteropBenchModeB.runBaselineB(InteropBenchFixtures.loadAll());
        InteropBenchModeB.freeze(res.resolve("mode-b-baseline.json"), b, "mode-b-baseline-b");
        InteropBenchModeB.Report post = InteropBenchModeB.runPostDetectors(InteropBenchFixtures.loadAll());
        InteropBenchModeB.freeze(res.resolve("mode-b-post-detector.json"), post, "mode-b-post-detector");
    }
}
