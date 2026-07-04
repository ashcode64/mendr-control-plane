package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Protected-path backstop tests (plan §3 / §4.4). The validator must reject any
 * transform that touches a blacklisted field, independent of rule type, while
 * leaving legitimate transforms deployable.
 */
class RuleValidatorTest {

    private RuleValidator.ValidationResult validate(Map<String, Object> rules) {
        return RuleValidator.validate(rules, null, List.of());
    }

    @Test
    void rejectsRenameOfAuthorizationHeader() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_RENAME",
                "mappings", Map.of("Authorization", "auth"));
        var result = validate(rules);
        assertFalse(result.deployable());
        assertTrue(result.reason().contains("protected path"));
    }

    @Test
    void rejectsRenameTargetingProtectedField() {
        // protected name appears as the rename VALUE (new name), not just the key
        var rules = Map.<String, Object>of(
                "type", "FIELD_RENAME",
                "mappings", Map.of("token", "internal_routing_id"));
        var result = validate(rules);
        assertFalse(result.deployable());
        assertTrue(result.reason().contains("internal_routing_id"));
    }

    @Test
    void rejectsCoercionOfCreditCardNumber() {
        var rules = Map.<String, Object>of(
                "type", "TYPE_COERCE",
                "coercions", Map.of("credit_card_number", "string"));
        assertFalse(validate(rules).deployable());
    }

    @Test
    void rejectsMoveWithProtectedPointerSegment() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_MOVE",
                "moves", List.of(Map.of("from", "/payment/credit_card_number", "to", "/cc")));
        var result = validate(rules);
        assertFalse(result.deployable());
        assertTrue(result.reason().contains("credit_card_number"));
    }

    @Test
    void allowsLegitimateRename() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_RENAME",
                "mappings", Map.of("customer_id", "customerId"));
        assertTrue(validate(rules).deployable());
    }

    @Test
    void allowsLegitimateTokenMove() {
        // the documented healing example must NOT be blocked
        var rules = Map.<String, Object>of(
                "type", "FIELD_MOVE",
                "moves", List.of(Map.of("from", "/credentials/token", "to", "/token")));
        assertTrue(validate(rules).deployable());
    }

    // ── SCALE value op (§12/§13) ─────────────────────────────────────────────

    @Test
    void allowsValidScaleWithPostCondition() {
        var rules = Map.<String, Object>of(
                "type", "SCALE",
                "scales", List.of(Map.of(
                        "path", "/amount", "numerator", 1, "denominator", 100,
                        "expectedMin", 0, "expectedMax", 1_000_000)));
        assertTrue(validate(rules).deployable());
    }

    @Test
    void rejectsScaleMissingPostCondition() {
        // no expectedMin/expectedMax — silent corruption would be undetectable
        var rules = Map.<String, Object>of(
                "type", "SCALE",
                "scales", List.of(Map.of("path", "/amount", "numerator", 1, "denominator", 100)));
        var result = validate(rules);
        assertFalse(result.deployable());
        assertTrue(result.reason().contains("post-condition"));
    }

    @Test
    void rejectsScaleWithZeroDenominator() {
        var rules = Map.<String, Object>of(
                "type", "SCALE",
                "scales", List.of(Map.of(
                        "path", "/amount", "numerator", 1, "denominator", 0,
                        "expectedMin", 0, "expectedMax", 100)));
        assertFalse(validate(rules).deployable());
    }

    @Test
    void rejectsScaleOnProtectedPath() {
        var rules = Map.<String, Object>of(
                "type", "SCALE",
                "scales", List.of(Map.of(
                        "path", "/internal_routing_id", "numerator", 1, "denominator", 100,
                        "expectedMin", 0, "expectedMax", 100)));
        assertFalse(validate(rules).deployable());
    }

    // ── COALESCE (§12, scenario 2) ───────────────────────────────────────────

    @Test
    void allowsValidCoalesce() {
        var rules = Map.<String, Object>of(
                "type", "COALESCE",
                "coalesce", List.of(Map.of("path", "/status", "value", "UNKNOWN")));
        assertTrue(validate(rules).deployable());
    }

    @Test
    void rejectsEmptyCoalesce() {
        var rules = Map.<String, Object>of("type", "COALESCE", "coalesce", List.of());
        assertFalse(validate(rules).deployable());
    }

    @Test
    void rejectsCoalesceWithRelativePath() {
        var rules = Map.<String, Object>of(
                "type", "COALESCE",
                "coalesce", List.of(Map.of("path", "status", "value", "UNKNOWN")));
        assertFalse(validate(rules).deployable());
    }

    @Test
    void rejectsCoalesceOnProtectedPath() {
        var rules = Map.<String, Object>of(
                "type", "COALESCE",
                "coalesce", List.of(Map.of("path", "/authorization", "value", "x")));
        assertFalse(validate(rules).deployable());
    }

    // ── MAP_VALUE (§12, scenario 6) ──────────────────────────────────────────

    @Test
    void allowsValidMapValue() {
        var rules = Map.<String, Object>of(
                "type", "MAP_VALUE",
                "valueMaps", List.of(Map.of(
                        "path", "/status",
                        "mapping", Map.of("A", "ACTIVE"),
                        "onUnmapped", "reject")));
        assertTrue(validate(rules).deployable());
    }

    @Test
    void rejectsMapValueWithEmptyMapping() {
        var rules = Map.<String, Object>of(
                "type", "MAP_VALUE",
                "valueMaps", List.of(Map.of("path", "/status", "mapping", Map.of())));
        var result = validate(rules);
        assertFalse(result.deployable());
        assertTrue(result.reason().contains("mapping"));
    }

    @Test
    void rejectsMapValueWithBadOnUnmappedPolicy() {
        var rules = Map.<String, Object>of(
                "type", "MAP_VALUE",
                "valueMaps", List.of(Map.of(
                        "path", "/status",
                        "mapping", Map.of("A", "ACTIVE"),
                        "onUnmapped", "guess")));
        assertFalse(validate(rules).deployable());
    }

    // ── REFORMAT_DATE (§12/§13, scenario 7) ──────────────────────────────────

    @Test
    void allowsValidReformatDate() {
        var rules = Map.<String, Object>of(
                "type", "REFORMAT_DATE",
                "dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "epoch_s", "targetFormat", "iso8601")));
        assertTrue(validate(rules).deployable());
    }

    @Test
    void rejectsReformatDateWithUnknownFormat() {
        var rules = Map.<String, Object>of(
                "type", "REFORMAT_DATE",
                "dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "%d/%m/%Y", "targetFormat", "iso8601")));
        assertFalse(validate(rules).deployable());
    }

    @Test
    void rejectsReformatDateWithSameSourceAndTarget() {
        var rules = Map.<String, Object>of(
                "type", "REFORMAT_DATE",
                "dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "iso8601", "targetFormat", "iso8601")));
        assertFalse(validate(rules).deployable());
    }

    @Test
    void allowsExpandedDateFormats() {
        // every new name in the widened allow-list is accepted
        for (String[] pair : new String[][] {
                {"iso8601_ms", "epoch_ms"},
                {"datetime", "iso8601"},
                {"date_slash", "date"},
                {"rfc1123", "epoch_s"}}) {
            var rules = Map.<String, Object>of(
                    "type", "REFORMAT_DATE",
                    "dateFormats", List.of(Map.of(
                            "path", "/created", "sourceFormat", pair[0], "targetFormat", pair[1])));
            assertTrue(validate(rules).deployable(), pair[0] + "->" + pair[1] + " should be deployable");
        }
    }

    @Test
    void allowsFixedOffsetAssumeTimezoneOnTzLessSource() {
        var rules = Map.<String, Object>of(
                "type", "REFORMAT_DATE",
                "dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "datetime", "targetFormat", "iso8601",
                        "assumeTimezone", "+05:30")));
        assertTrue(validate(rules).deployable());
    }

    @Test
    void rejectsNamedZoneAssumeTimezone() {
        var rules = Map.<String, Object>of(
                "type", "REFORMAT_DATE",
                "dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "datetime", "targetFormat", "iso8601",
                        "assumeTimezone", "America/New_York")));
        var result = validate(rules);
        assertFalse(result.deployable());
        assertTrue(result.reason().contains("fixed offset"));
    }

    @Test
    void rejectsAssumeTimezoneOnZoneBearingSource() {
        // iso8601 carries its own zone — assumeTimezone is meaningless and rejected
        var rules = Map.<String, Object>of(
                "type", "REFORMAT_DATE",
                "dateFormats", List.of(Map.of(
                        "path", "/created", "sourceFormat", "iso8601", "targetFormat", "date",
                        "assumeTimezone", "+05:30")));
        assertFalse(validate(rules).deployable());
    }

    // ── STRIP_UNKNOWN (§12, scenario 5) ──────────────────────────────────────

    @Test
    void allowsValidStripUnknown() {
        var rules = Map.<String, Object>of(
                "type", "STRIP_UNKNOWN",
                "stripUnknown", List.of(Map.of("path", "/", "allowed", List.of("id", "name"))));
        assertTrue(validate(rules).deployable());
    }

    @Test
    void rejectsStripUnknownWithEmptyAllowList() {
        var rules = Map.<String, Object>of(
                "type", "STRIP_UNKNOWN",
                "stripUnknown", List.of(Map.of("path", "/", "allowed", List.of())));
        assertFalse(validate(rules).deployable());
    }

    // ── WRAP_ARRAY / UNWRAP_ARRAY (§12, scenario 11) ─────────────────────────

    @Test
    void allowsValidWrapArray() {
        var rules = Map.<String, Object>of(
                "type", "WRAP_ARRAY",
                "wrapArrays", List.of(Map.of("path", "/item")));
        assertTrue(validate(rules).deployable());
    }

    @Test
    void rejectsWrapArrayWithRelativePath() {
        var rules = Map.<String, Object>of(
                "type", "WRAP_ARRAY",
                "wrapArrays", List.of(Map.of("path", "item")));
        assertFalse(validate(rules).deployable());
    }

    @Test
    void rejectsUnwrapArrayOnProtectedPath() {
        var rules = Map.<String, Object>of(
                "type", "UNWRAP_ARRAY",
                "unwrapArrays", List.of(Map.of("path", "/authorization")));
        assertFalse(validate(rules).deployable());
    }

    // ── Ground-truth pointer validation (FIELD_MOVE / FIELD_RENAME) ───────────

    @Test
    void rejectsMoveWhoseSourceIsAbsentFromPayload() {
        // The reported bug: model proposes moving a field that does not exist in the
        // payload (it was renamed away) — a fail-open no-op that must be rejected.
        var rules = Map.<String, Object>of(
                "type", "FIELD_MOVE",
                "moves", List.of(Map.of("from", "/obj_id/item_id/tag_sent", "to", "/tag_sent")));
        Map<String, Object> payload = Map.of(
                "obj_id", Map.of("item_id", Map.of("transmission_id", "TXN-1")));
        var result = RuleValidator.validate(rules, null, List.of(), payload);
        assertFalse(result.deployable());
        assertTrue(result.reason().contains("not found in actual payload"));
    }

    @Test
    void allowsMoveWhoseSourceExistsInPayload() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_MOVE",
                "moves", List.of(Map.of("from", "/obj_id/item_id/tag_sent", "to", "/tag_sent")));
        Map<String, Object> payload = Map.of(
                "obj_id", Map.of("item_id", Map.of("tag_sent", "TXN-1")));
        assertTrue(RuleValidator.validate(rules, null, List.of(), payload).deployable());
    }

    @Test
    void rejectsRenameWhoseSourceIsAbsentFromPayload() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_RENAME",
                "mappings", Map.of("tag_sent", "transmission_id"));
        Map<String, Object> payload = Map.of("transmission_id", "TXN-1");
        var result = RuleValidator.validate(rules, null, List.of(), payload);
        assertFalse(result.deployable());
        assertTrue(result.reason().contains("no-op rename"));
    }

    @Test
    void allowsRenameWhoseSourceExistsInPayload() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_RENAME",
                "mappings", Map.of("transmission_id", "tag_sent"));
        Map<String, Object> payload = Map.of("transmission_id", "TXN-1");
        assertTrue(RuleValidator.validate(rules, null, List.of(), payload).deployable());
    }

    @Test
    void groundTruthCheckSkippedWhenPayloadNull() {
        // 3-arg overload (no payload) keeps the legacy shape: syntax-only.
        var rules = Map.<String, Object>of(
                "type", "FIELD_MOVE",
                "moves", List.of(Map.of("from", "/nope/missing", "to", "/x")));
        assertTrue(validate(rules).deployable());
    }

    // ── Context-prefix normalization (repair leaked wrapper pointers) ─────────

    @Test
    void stripsLeakedContextPrefixFromMovePointers() {
        Map<String, Object> rules = new java.util.HashMap<>();
        rules.put("type", "FIELD_MOVE");
        rules.put("moves", List.of(new java.util.HashMap<>(Map.of(
                "from", "/actualRequestPayload/obj_id/tag_sent",
                "to", "/tag_sent"))));
        int repaired = RuleValidator.normalizeContextPointers(rules);
        assertEquals(1, repaired);
        @SuppressWarnings("unchecked")
        Map<String, Object> move = ((List<Map<String, Object>>) rules.get("moves")).get(0);
        assertEquals("/obj_id/tag_sent", move.get("from"));
        assertEquals("/tag_sent", move.get("to"));
    }

    @Test
    void leavesCleanPointersUntouched() {
        Map<String, Object> rules = new java.util.HashMap<>();
        rules.put("type", "FIELD_MOVE");
        rules.put("moves", List.of(new java.util.HashMap<>(Map.of(
                "from", "/credentials/token", "to", "/token"))));
        assertEquals(0, RuleValidator.normalizeContextPointers(rules));
    }

    @Test
    void stripContextPrefixOnlyAffectsFirstSegment() {
        // a legitimate nested field named 'schema' deeper in the tree is preserved
        assertEquals("/a/schema/b", RuleValidator.stripContextPrefix("/a/schema/b"));
        assertEquals("/obj_id/tag", RuleValidator.stripContextPrefix("/actualRequestPayload/obj_id/tag"));
    }

    // ── Effect preview (item 4: deterministic no-op detection) ────────────────

    @Test
    void describeEffectFlagsNoOpMove() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_MOVE",
                "moves", List.of(Map.of("from", "/obj_id/item_id/tag_sent", "to", "/tag_sent")));
        Map<String, Object> payload = Map.of(
                "obj_id", Map.of("item_id", Map.of("transmission_id", "TXN-1")));
        var effect = RuleValidator.describeEffect(rules, payload);
        assertFalse(effect.effective());
        assertTrue(effect.reason().contains("absent from payload"));
    }

    @Test
    void describeEffectReportsEffectiveForResolvableMove() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_MOVE",
                "moves", List.of(Map.of("from", "/obj_id/item_id/tag_sent", "to", "/tag_sent")));
        Map<String, Object> payload = Map.of(
                "obj_id", Map.of("item_id", Map.of("tag_sent", "TXN-1")));
        assertTrue(RuleValidator.describeEffect(rules, payload).effective());
    }

    @Test
    void describeEffectIsNeutralWithoutPayload() {
        var rules = Map.<String, Object>of(
                "type", "FIELD_MOVE",
                "moves", List.of(Map.of("from", "/x", "to", "/y")));
        assertTrue(RuleValidator.describeEffect(rules, null).effective());
    }
}
