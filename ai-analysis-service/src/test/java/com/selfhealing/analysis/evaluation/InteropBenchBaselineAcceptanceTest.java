package com.selfhealing.analysis.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 5: compare live InteropBench to <em>committed</em> freeze files.
 * Never rewrite {@code src/test/resources/interop/baselines}.
 */
class InteropBenchBaselineAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void committedModeAFreezeIsNotRewrittenAndLiveMatches() throws Exception {
        Map<?, ?> pre = read("interop/baselines/mode-a-pre-detector.json");
        Map<?, ?> post = read("interop/baselines/mode-a-post-detector.json");
        assertEquals("pre-detector", pre.get("label"));
        assertEquals("post-detector", post.get("label"));

        InteropBenchModeA.Report live = InteropBenchModeA.run(InteropBenchFixtures.loadAll());
        @SuppressWarnings("unchecked")
        Map<String, Object> postSum = (Map<String, Object>) post.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> preSum = (Map<String, Object>) pre.get("summary");
        double frozenValue = ((Number) postSum.get("value_pass@1")).doubleValue();
        double frozenStruct = ((Number) postSum.get("structural_pass@1")).doubleValue();
        double preStruct = ((Number) preSum.get("structural_pass@1")).doubleValue();
        assertTrue(frozenValue >= 0.95);
        assertEquals(0, ((Number) postSum.get("negative_false_positives")).intValue());
        assertTrue(Math.abs(live.valuePassAt1() - frozenValue) <= 0.02 + 1e-9);
        assertTrue(Math.abs(live.structuralPassAt1() - frozenStruct) <= 0.02 + 1e-9);
        assertTrue(frozenStruct + 1e-9 >= preStruct - 0.02);
        assertEquals(0, live.negativeFalsePositives());
    }

    @Test
    void committedModeBFreezeComparedToLiveWithoutRewrite() throws Exception {
        Map<?, ?> bFile = read("interop/baselines/mode-b-baseline.json");
        Map<?, ?> postFile = read("interop/baselines/mode-b-post-detector.json");
        Map<?, ?> pilot = read("interop/baselines/throughput-pilot.json");
        assertEquals("mode-b-baseline-b", bFile.get("label"));
        assertEquals("mode-b-post-detector", postFile.get("label"));
        assertEquals("throughput-pilot", pilot.get("label"));
        assertNotNull(pilot.get("measuredLlmHttpCallsPerDiagnose"));
        assertNotNull(pilot.get("benchTenantApplied"));

        @SuppressWarnings("unchecked")
        Map<String, Object> bSum = (Map<String, Object>) bFile.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> postSum = (Map<String, Object>) postFile.get("summary");
        double b = ((Number) bSum.get("value_pass@1")).doubleValue();
        double post = ((Number) postSum.get("value_pass@1")).doubleValue();
        double need = b < 0.70 ? b + 0.25 : 0.90;
        assertTrue(post + 1e-9 >= need, "frozen post value=" + post + " vs b=" + b);

        InteropBenchModeB.Report livePost =
                InteropBenchModeB.runPostDetectors(InteropBenchFixtures.loadAll());
        assertTrue(Math.abs(livePost.valuePassAt1() - post) <= 0.02 + 1e-9);
        assertTrue(livePost.totalTokens() > 0);
        assertTrue(((Number) postSum.get("total_tokens")).doubleValue() > 0);
        assertTrue(((Number) postSum.get("mean_eqsat_delta")).doubleValue() > 0);
        double attr = ((Number) postSum.get("shadow_attribution_rate")).doubleValue();
        assertTrue(attr >= 0.90);
        @SuppressWarnings("unchecked")
        Map<String, Object> notes = (Map<String, Object>) postSum.get("notes");
        assertEquals(10, ((Number) notes.get("N")).intValue());
    }

    @Test
    void freezeFlagRequiredToWriteResources() {
        assertFalse(InteropBenchBaseline.freezeWritesEnabled());
        assertThrows(IllegalStateException.class, () ->
                InteropBenchBaseline.writeIfAllowed(
                        Path.of("src/test/resources/interop/baselines/should-not-write.json"),
                        Map.of("no", true)));
    }

    private static Map<?, ?> read(String resource) throws Exception {
        try (InputStream in = InteropBenchBaselineAcceptanceTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing committed freeze " + resource
                    + " — re-baseline with -Dmendr.interop.freeze=true");
            return MAPPER.readValue(in, Map.class);
        }
    }
}
