package com.selfhealing.analysis.service.ddmin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubsetStillDriftsTest {

    @Test
    void typeMismatchWithExpectedTypeStillDrifts() {
        var field = new DdminLocalizer.FieldCandidate("/amount", "TYPE_COERCE", "number", "string");
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("amount", "10"))).isTrue();
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("amount", 10))).isFalse();
    }

    @Test
    void nullExpectedTypeUsesObservedType() {
        var field = new DdminLocalizer.FieldCandidate("/amount", "TYPE_COERCE", null, "string");
        // Still the bad observed type → drifts
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("amount", "10"))).isTrue();
        // Changed away from observed type → not this drift
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("amount", 10))).isFalse();
    }

    @Test
    void opaqueWithOnlyJsonPathNeverFalsePass() {
        var field = new DdminLocalizer.FieldCandidate("/mystery", "UNKNOWN", null, null);
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("mystery", 1))).isTrue();
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("other", 1))).isTrue();
    }

    @Test
    void renameMissingPathStillDrifts() {
        var field = new DdminLocalizer.FieldCandidate("/newName", "FIELD_RENAME", null, null);
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("oldName", 1))).isTrue();
    }

    @Test
    void removeStillPresentDrifts() {
        var field = new DdminLocalizer.FieldCandidate("/gone", "REMOVE_FIELD", null, null);
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("gone", 1))).isTrue();
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("keep", 1))).isFalse();
    }

    @Test
    void addMissingStillDrifts() {
        var field = new DdminLocalizer.FieldCandidate("/needed", "ADD_DEFAULT", "string", null);
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("other", 1))).isTrue();
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("needed", ""))).isFalse();
    }

    @Test
    void nonMapOutputWithSubsetStillDrifts() {
        var field = new DdminLocalizer.FieldCandidate("/a", "TYPE_COERCE", "string", "number");
        assertThat(DdminOracleService.subsetStillDrifts(List.of(field), "not-json")).isTrue();
        assertThat(DdminOracleService.subsetStillDrifts(List.of(field), List.of())).isTrue();
    }

    @Test
    void emptySubsetNeverDrifts() {
        assertThat(DdminOracleService.subsetStillDrifts(List.of(), Map.of("a", 1))).isFalse();
    }

    @Test
    void textBodyRemoveStillPresent() {
        var field = new DdminLocalizer.FieldCandidate("/secret", "REMOVE_FIELD", null, null);
        assertThat(DdminOracleService.subsetStillDriftsInText(
                List.of(field), "{\"secret\":1}")).isTrue();
        assertThat(DdminOracleService.subsetStillDriftsInText(
                List.of(field), "{\"ok\":1}")).isFalse();
    }

    @Test
    void textBodyOpaqueWithoutExpectedTypeStillDrifts() {
        var field = new DdminLocalizer.FieldCandidate("/x", "UNKNOWN", null, null);
        assertThat(DdminOracleService.subsetStillDriftsInText(
                List.of(field), "<html>ok</html>")).isTrue();
    }

    @Test
    void nestedPointerResolved() {
        var field = new DdminLocalizer.FieldCandidate("/order/amount", "TYPE_COERCE", "number", "string");
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("order", Map.of("amount", "9")))).isTrue();
        assertThat(DdminOracleService.subsetStillDrifts(
                List.of(field), Map.of("order", Map.of("amount", 9)))).isFalse();
    }
}
