package com.selfhealing.analysis.service.registry;

import com.selfhealing.analysis.service.SchemaDiffResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UnitDateDetectorTest {

    @Test
    void firesOnKmhToMphConjunction() {
        SchemaDiffResult diff = UnitDateDetector.detect(
                Map.of("speed_kmh", 100),
                Map.of("speed_mph", 0),
                UnitDateDetector.DetectorConfig.defaults());
        assertTrue(diff.hasDeterministicRule());
        assertEquals(SchemaDiffResult.Kind.UNIT_SCALE, diff.kind());
        assertNotNull(diff.registryRuleId());
        assertFalse(diff.ops().isEmpty());
        assertTrue(diff.coverageComplete());
    }

    @Test
    void firesOnIsoToEpochDate() {
        SchemaDiffResult diff = UnitDateDetector.detect(
                Map.of("event_iso", "2020-01-01T00:00:00Z"),
                Map.of("event_epoch", 0),
                UnitDateDetector.DetectorConfig.defaults());
        assertTrue(diff.hasDeterministicRule());
        assertEquals(SchemaDiffResult.Kind.DATE_FORMAT, diff.kind());
    }

    @Test
    void firesOnCelsiusToFahrenheit() {
        SchemaDiffResult diff = UnitDateDetector.detect(
                Map.of("temp_c", 0),
                Map.of("temp_f", 32),
                UnitDateDetector.DetectorConfig.defaults());
        assertTrue(diff.hasDeterministicRule());
        assertTrue(diff.ops().stream().anyMatch(op -> "arith".equals(op.get("op"))));
        assertTrue(diff.ops().stream().anyMatch(op -> "scale".equals(op.get("op"))));
    }

    @Test
    void firesOnIso8601ToEpochMsCompoundAffix() {
        SchemaDiffResult diff = UnitDateDetector.detect(
                Map.of("event_iso8601", "2020-01-01T00:00:00Z"),
                Map.of("event_epoch_ms", 0),
                UnitDateDetector.DetectorConfig.defaults());
        assertTrue(diff.hasDeterministicRule());
        assertEquals(SchemaDiffResult.Kind.DATE_FORMAT, diff.kind());
    }

    @Test
    void coveragePartialWhenOtherFieldsDiffer() {
        SchemaDiffResult partial = UnitDateDetector.detect(
                Map.of("speed_kmh", 100, "extra_field", "x"),
                Map.of("speed_mph", 0, "other_missing", 1),
                UnitDateDetector.DetectorConfig.defaults());
        assertTrue(partial.hasDeterministicRule());
        assertFalse(partial.coverageComplete());
    }

    @Test
    void dateMatchingDoesNotUseRawSubstringContains() {
        SchemaDiffResult diff = UnitDateDetector.detect(
                Map.of("myepochalvalue", 1),
                Map.of("myiso8601ish", "2020-01-01T00:00:00Z"),
                UnitDateDetector.DetectorConfig.defaults());
        assertFalse(diff.hasDeterministicRule());
    }

    @Test
    void abstainsOnParkingMeters() {
        SchemaDiffResult diff = UnitDateDetector.detect(
                Map.of("parking_meters", 12),
                Map.of("parking_meters", 12),
                UnitDateDetector.DetectorConfig.defaults());
        assertFalse(diff.hasDeterministicRule());
    }

    @Test
    void abstainsOnOneSidedUnitToken() {
        SchemaDiffResult diff = UnitDateDetector.detect(
                Map.of("speed_kmh", 10),
                Map.of("speed", 10),
                UnitDateDetector.DetectorConfig.defaults());
        assertFalse(diff.hasDeterministicRule());
    }

    @Test
    void abstainsOnStringTypedUnitField() {
        SchemaDiffResult diff = UnitDateDetector.detect(
                Map.of("speed_kmh", "fast"),
                Map.of("speed_mph", "fast"),
                UnitDateDetector.DetectorConfig.defaults());
        assertFalse(diff.hasDeterministicRule());
    }

    @Test
    void respectsDenylist() {
        SchemaDiffResult hit = UnitDateDetector.detect(
                Map.of("speed_kmh", 100),
                Map.of("speed_mph", 0),
                UnitDateDetector.DetectorConfig.defaults());
        assertTrue(hit.hasDeterministicRule());
        SchemaDiffResult denied = UnitDateDetector.detect(
                Map.of("speed_kmh", 100),
                Map.of("speed_mph", 0),
                new UnitDateDetector.DetectorConfig(true, true, Set.of(hit.registryRuleId())));
        assertFalse(denied.hasDeterministicRule());
    }
}
