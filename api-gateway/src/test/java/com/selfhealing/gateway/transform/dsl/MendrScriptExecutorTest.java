package com.selfhealing.gateway.transform.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MendrScriptExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MendrScriptExecutor executor = new MendrScriptExecutor();
    private final MendrScriptVerifier verifier = new MendrScriptVerifier();

    private MendrProgram parse(String json) {
        try {
            return mapper.readValue(json, MendrProgram.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> input(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void roundTripsAstThroughJacksonPolymorphism() {
        MendrProgram p = parse("""
            {"schemaVersion":"mendrscript/v1","ops":[
              {"op":"rename","from":"/userName","to":"/user_name"},
              {"op":"default","path":"/active","value":true,"on":"ABSENT"}
            ]}""");
        assertEquals(2, p.ops().size());
        assertInstanceOf(Op.Rename.class, p.ops().get(0));
        assertInstanceOf(Op.Default.class, p.ops().get(1));
    }

    @Test
    void renameAndDefaultExecute() {
        MendrProgram p = parse("""
            {"ops":[
              {"op":"rename","from":"/userName","to":"/user_name"},
              {"op":"default","path":"/active","value":true,"on":"ABSENT"}
            ]}""");
        assertTrue(verifier.verify(p).valid());
        Object out = executor.execute(p, input("{\"userName\":\"jo\"}"));
        Map<String, Object> m = (Map<String, Object>) out;
        assertEquals("jo", m.get("user_name"));
        assertFalse(m.containsKey("userName"));
        assertEquals(true, m.get("active"));
    }

    @Test
    void defaultTriggerParsesCaseInsensitively() {
        // The LLM prompt instructs lowercase on:"absent|null|both"; the edge uppercases.
        // Jackson enum binding must accept lowercase or the basic default op never verifies.
        MendrProgram p = parse("""
            {"ops":[{"op":"default","path":"/active","value":true,"on":"absent"}]}""");
        assertTrue(verifier.verify(p).valid());
        assertEquals(Op.Trigger.ABSENT, ((Op.Default) p.ops().get(0)).on());
        Map<String, Object> out = (Map<String, Object>) executor.execute(p, input("{}"));
        assertEquals(true, out.get("active"));
    }

    @Test
    void scaleAppliesRationalFactorAndPostcondition() {
        MendrProgram p = parse("""
            {"ops":[{"op":"scale","path":"/amount","numerator":1,"denominator":100,
                     "expectedMin":0,"expectedMax":1000000}]}""");
        assertTrue(verifier.verify(p).valid());
        Map<String, Object> out = (Map<String, Object>) executor.execute(p, input("{\"amount\":2500}"));
        assertEquals(25L, out.get("amount"));
    }

    @Test
    void scaleFailsClosedWhenPostconditionViolated() {
        MendrProgram p = parse("""
            {"ops":[{"op":"scale","path":"/amount","numerator":1000,"denominator":1,
                     "expectedMin":0,"expectedMax":100}]}""");
        assertTrue(verifier.verify(p).valid());
        assertThrows(MendrScriptRuntimeException.class,
                () -> executor.execute(p, input("{\"amount\":50}")));
    }

    @Test
    void conditionalRunsThenBranchOnFormatMatch() {
        MendrProgram p = parse("""
            {"ops":[{"op":"conditional",
                "predicate":{"op":"matches_format","path":"/email","format":"email"},
                "then":[{"op":"default","path":"/verified","value":true,"on":"ABSENT"}],
                "otherwise":[{"op":"default","path":"/verified","value":false,"on":"ABSENT"}]}]}""");
        assertTrue(verifier.verify(p).valid());
        Map<String, Object> ok = (Map<String, Object>) executor.execute(p, input("{\"email\":\"a@b.com\"}"));
        assertEquals(true, ok.get("verified"));
        Map<String, Object> bad = (Map<String, Object>) executor.execute(p, input("{\"email\":\"nope\"}"));
        assertEquals(false, bad.get("verified"));
    }

    @Test
    void verifierRejectsProtectedPath() {
        MendrProgram p = parse("""
            {"ops":[{"op":"rename","from":"/authorization","to":"/auth"}]}""");
        var result = verifier.verify(p);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("protected path")));
    }

    @Test
    void verifierRejectsScaleWithoutPostcondition() {
        MendrProgram p = parse("""
            {"ops":[{"op":"scale","path":"/amount","numerator":1,"denominator":100}]}""");
        var result = verifier.verify(p);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("expectedMin")));
    }

    @Test
    void verifierRejectsDefaultWithoutTrigger() {
        MendrProgram p = parse("""
            {"ops":[{"op":"default","path":"/active","value":true}]}""");
        var result = verifier.verify(p);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("default.on")));
    }

    @Test
    void verifierRejectsReadBeforeWrite() {
        MendrProgram p = parse("""
            {"ops":[
              {"op":"copy","from":"/computed","to":"/echo"},
              {"op":"copy","from":"/src","to":"/computed"}
            ]}""");
        var result = verifier.verify(p);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("dataflow")));
    }

    @Test
    void scaleUsesSameOperationOrderAsEdge() {
        // (value * numerator) / denominator — NOT value * (numerator/denominator).
        // For 12345 /100 the two orders differ by a ULP; the edge uses the former.
        MendrProgram p = parse("""
            {"ops":[{"op":"scale","path":"/amount","numerator":1,"denominator":100,
                     "expectedMin":0,"expectedMax":1000000}]}""");
        assertTrue(verifier.verify(p).valid());
        Map<String, Object> out = (Map<String, Object>) executor.execute(p, input("{\"amount\":12345}"));
        assertEquals(123.45, out.get("amount"));
    }

    @Test
    void reformatDateConvertsDateToIso8601Utc() {
        MendrProgram p = parse("""
            {"ops":[{"op":"reformat_date","path":"/d","sourceFormat":"date",
                     "targetFormat":"iso8601","tzPolicy":"utc"}]}""");
        assertTrue(verifier.verify(p).valid());
        Map<String, Object> out = (Map<String, Object>) executor.execute(p, input("{\"d\":\"2023-01-01\"}"));
        assertEquals("2023-01-01T00:00:00Z", out.get("d"));
    }

    @Test
    void reformatDateIso8601WithoutZoneFailsClosed() {
        // The edge requires an explicit zone; a zone-less ISO string must fail-closed
        // here too (no silent UTC assumption), so the simulator records a counterexample.
        MendrProgram p = parse("""
            {"ops":[{"op":"reformat_date","path":"/d","sourceFormat":"iso8601",
                     "targetFormat":"epoch_s","tzPolicy":"utc"}]}""");
        assertTrue(verifier.verify(p).valid());
        assertThrows(MendrScriptRuntimeException.class,
                () -> executor.execute(p, input("{\"d\":\"2023-01-01T00:00:00\"}")));
    }

    @Test
    void reformatDateAppliesTzPolicyOffsetToZonelessDateInput() {
        // date input is assumed to be in tzPolicy's zone (matches the edge's assume_off).
        MendrProgram p = parse("""
            {"ops":[{"op":"reformat_date","path":"/d","sourceFormat":"date",
                     "targetFormat":"epoch_ms","tzPolicy":"+05:30"}]}""");
        assertTrue(verifier.verify(p).valid());
        Map<String, Object> out = (Map<String, Object>) executor.execute(p, input("{\"d\":\"2023-01-01\"}"));
        assertEquals(1672511400000L, out.get("d")); // 2023-01-01T00:00:00+05:30 in epoch ms
    }

    @Test
    void reformatDateFailsClosedOutsideValidityWindow() {
        MendrProgram p = parse("""
            {"ops":[{"op":"reformat_date","path":"/d","sourceFormat":"date",
                     "targetFormat":"epoch_ms","tzPolicy":"utc"}]}""");
        assertTrue(verifier.verify(p).valid());
        // Beyond 2100-01-01 -> out of window (the edge's os.date is undefined for these).
        assertThrows(MendrScriptRuntimeException.class,
                () -> executor.execute(p, input("{\"d\":\"2200-01-01\"}")));
        // Pre-1970 -> negative epoch -> out of window.
        assertThrows(MendrScriptRuntimeException.class,
                () -> executor.execute(p, input("{\"d\":\"1960-01-01\"}")));
    }

    @Test
    void coerceToDoubleOfIntegerEncodesWithoutDecimal() {
        MendrProgram p = parse("""
            {"ops":[{"op":"coerce","path":"/n","targetType":"double"}]}""");
        assertTrue(verifier.verify(p).valid());
        Map<String, Object> out = (Map<String, Object>) executor.execute(p, input("{\"n\":5}"));
        assertEquals(5L, out.get("n"));
    }

    @Test
    void trimRemovesAsciiWhitespaceOnlyLikeEdge() {
        MendrProgram p = parse("""
            {"ops":[{"op":"string","path":"/s","operation":"trim"}]}""");
        assertTrue(verifier.verify(p).valid());
        Map<String, Object> ascii = (Map<String, Object>) executor.execute(p, input("{\"s\":\"  hi \\t\"}"));
        assertEquals("hi", ascii.get("s"));
        // U+00A0 (non-breaking space) is NOT ASCII whitespace; Lua %s leaves it, so we must too.
        Map<String, Object> nbsp = (Map<String, Object>) executor.execute(p, input("{\"s\":\"\\u00A0hi\\u00A0\"}"));
        assertEquals("\u00A0hi\u00A0", nbsp.get("s"));
    }

    @Test
    void simulatorReportsFaultAsCounterexample() {
        MendrProgram p = parse("""
            {"ops":[{"op":"scale","path":"/amount","numerator":1,"denominator":100,
                     "expectedMin":0,"expectedMax":10}]}""");
        var report = new TransformSimulator(executor).simulate(p, List.of(
                new TransformSimulator.Case(input("{\"amount\":500}"), null),
                new TransformSimulator.Case(input("{\"amount\":100000}"), null)));
        assertEquals(1, report.passed());
        assertEquals(1, report.faulted());
    }
}
