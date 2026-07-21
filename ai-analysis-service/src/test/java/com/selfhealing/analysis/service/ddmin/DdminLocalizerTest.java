package com.selfhealing.analysis.service.ddmin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdminLocalizerTest {

    private DdminLocalizer localizer;

    @BeforeEach
    void setUp() {
        localizer = new DdminLocalizer();
        ReflectionTestUtils.setField(localizer, "maxFields", 32);
        ReflectionTestUtils.setField(localizer, "abortNonSafe", true);
    }

    @Test
    void pathASchemaUsesOfflineOracle() {
        List<DdminLocalizer.FieldCandidate> fields = List.of(
                new DdminLocalizer.FieldCandidate("/a", "TYPE_COERCE", "string", "number"),
                new DdminLocalizer.FieldCandidate("/b", "TYPE_COERCE", "string", "number"));
        var result = localizer.localize("SCHEMA_MISMATCH", "POST", null, fields,
                subset -> subset.isEmpty() ? OracleOutcome.PASS : OracleOutcome.FAIL);
        assertThat(result.path()).isEqualTo(DdminOraclePath.PATH_A_SCHEMA);
        assertThat(result.aborted()).isFalse();
        assertThat(result.minimal()).hasSize(1);
    }

    @Test
    void pathCPostOpaqueAborts() {
        List<DdminLocalizer.FieldCandidate> fields = List.of(
                new DdminLocalizer.FieldCandidate("/pay", "UNKNOWN", null, null),
                new DdminLocalizer.FieldCandidate("/amt", "UNKNOWN", null, null));
        var result = localizer.localize("UNKNOWN", "POST", null, fields,
                subset -> OracleOutcome.FAIL);
        assertThat(result.path()).isEqualTo(DdminOraclePath.PATH_C_ABORT_HITL);
        assertThat(result.aborted()).isTrue();
        assertThat(result.oracleCalls()).isZero();
        assertThat(result.abortReason()).contains("unsafe");
    }

    @Test
    void putOpaqueAbortsEvenThoughIdempotent() {
        List<DdminLocalizer.FieldCandidate> fields = List.of(
                new DdminLocalizer.FieldCandidate("/a", "UNKNOWN", null, null),
                new DdminLocalizer.FieldCandidate("/b", "UNKNOWN", null, null));
        var result = localizer.localize("RESPONSE_MISMATCH", "PUT", null, fields,
                subset -> OracleOutcome.FAIL);
        assertThat(result.path()).isEqualTo(DdminOraclePath.PATH_C_ABORT_HITL);
        assertThat(result.aborted()).isTrue();
    }

    @Test
    void deleteOpaqueAborts() {
        List<DdminLocalizer.FieldCandidate> fields = List.of(
                new DdminLocalizer.FieldCandidate("/a", "UNKNOWN", null, null),
                new DdminLocalizer.FieldCandidate("/b", "UNKNOWN", null, null));
        var result = localizer.localize("UNKNOWN", "DELETE", null, fields,
                subset -> OracleOutcome.FAIL);
        assertThat(result.path()).isEqualTo(DdminOraclePath.PATH_C_ABORT_HITL);
    }

    @Test
    void responseMismatchPostAbortsToPathC() {
        List<DdminLocalizer.FieldCandidate> fields = List.of(
                new DdminLocalizer.FieldCandidate("/a", "RESPONSE_TYPE_COERCE", "string", "number"),
                new DdminLocalizer.FieldCandidate("/b", "RESPONSE_TYPE_COERCE", "string", "number"));
        var result = localizer.localize("RESPONSE_MISMATCH", "POST", null, fields,
                subset -> OracleOutcome.FAIL);
        assertThat(result.path()).isEqualTo(DdminOraclePath.PATH_C_ABORT_HITL);
        assertThat(result.aborted()).isTrue();
    }

    @Test
    void responseMismatchGetAllowsPathB() {
        List<DdminLocalizer.FieldCandidate> fields = List.of(
                new DdminLocalizer.FieldCandidate("/a", "RESPONSE_TYPE_COERCE", null, null),
                new DdminLocalizer.FieldCandidate("/b", "RESPONSE_TYPE_COERCE", null, null));
        var result = localizer.localize("RESPONSE_MISMATCH", "GET", null, fields,
                subset -> subset.isEmpty() ? OracleOutcome.PASS : OracleOutcome.FAIL);
        assertThat(result.path()).isEqualTo(DdminOraclePath.PATH_B_SAFE_LIVE);
        assertThat(result.aborted()).isFalse();
    }

    @Test
    void unresolvedNeverCoerced() {
        List<DdminLocalizer.FieldCandidate> fields = List.of(
                new DdminLocalizer.FieldCandidate("/oneOf/x", "TYPE_COERCE", "string", "number"),
                new DdminLocalizer.FieldCandidate("/y", "TYPE_COERCE", "string", "number"));
        var result = localizer.localize("SCHEMA_MISMATCH", "PUT", null, fields, subset -> {
            for (var f : subset) {
                if (f.jsonPath() != null && f.jsonPath().contains("oneOf")) {
                    return OracleOutcome.UNRESOLVED;
                }
            }
            return subset.isEmpty() ? OracleOutcome.PASS : OracleOutcome.FAIL;
        });
        assertThat(result.aborted()).isFalse();
        assertThat(result.minimal()).isNotEmpty();
    }

    @Test
    void precisePointerSkipsDdmin() {
        var result = localizer.localize("UNKNOWN", "POST", "/exact",
                List.of(new DdminLocalizer.FieldCandidate("/exact", "TYPE_COERCE", "s", "n")),
                subset -> OracleOutcome.FAIL);
        assertThat(result.path()).isEqualTo(DdminOraclePath.SKIP_LOCALIZED);
        assertThat(result.oracleCalls()).isZero();
    }
}
