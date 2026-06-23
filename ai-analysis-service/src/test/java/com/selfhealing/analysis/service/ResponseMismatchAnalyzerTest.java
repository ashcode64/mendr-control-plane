package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseMismatchAnalyzerTest {

    private static final Map<String, Object> EXPECTED = Map.of(
            "transactionId", "TXN-1",
            "orderRef", "ORD-1",
            "status", "SUCCESS",
            "approvalCode", "AUTH-XYZ",
            "amount", 49.99,
            "currency", "USD"
    );

    private static final Map<String, Object> PROVIDER_WRONG = Map.of(
            "transaction_id", "TXN-1",
            "order_ref", "ORD-1",
            "status", "SUCCESS",
            "amount", "49.99",
            "currency", "USD"
    );

    @Test
    void compositeBadResponse_primaryIsMissingField() {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("transaction_id", "TXN-1");
        actual.put("order_ref", "ORD-1");
        actual.put("status", "SUCCESS");
        actual.put("amount", "49.99");
        actual.put("currency", "USD");

        ResponseDiffResult diff = ResponseMismatchAnalyzer.analyze(actual, EXPECTED, PROVIDER_WRONG);

        assertEquals(ResponseDiffResult.Kind.MISSING_FIELD, diff.primaryKind());
        assertTrue(diff.hasDeterministicRule());
        assertTrue(diff.missingFields().contains("approvalCode"));
        assertFalse(diff.renameMappings().isEmpty());
        assertFalse(diff.typeCoercions().isEmpty());
        assertEquals("RESPONSE_ADD_DEFAULT", diff.toTransformationRules().get("type"));
    }

    @Test
    void afterMissingFixed_primaryIsFieldRename() {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("transaction_id", "TXN-1");
        actual.put("order_ref", "ORD-1");
        actual.put("status", "SUCCESS");
        actual.put("approvalCode", "AUTH-PENDING");
        actual.put("amount", "49.99");
        actual.put("currency", "USD");

        ResponseDiffResult diff = ResponseMismatchAnalyzer.analyze(actual, EXPECTED, PROVIDER_WRONG);

        assertEquals(ResponseDiffResult.Kind.FIELD_RENAME, diff.primaryKind());
        assertTrue(diff.missingFields().isEmpty());
        assertTrue(diff.renameMappings().containsKey("transaction_id"));
        assertEquals("RESPONSE_FIELD_RENAME", diff.toTransformationRules().get("type"));
    }

    @Test
    void afterMissingAndRenameFixed_primaryIsTypeCoerce() {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("transactionId", "TXN-1");
        actual.put("orderRef", "ORD-1");
        actual.put("status", "SUCCESS");
        actual.put("approvalCode", "AUTH-PENDING");
        actual.put("amount", "49.99");
        actual.put("currency", "USD");

        ResponseDiffResult diff = ResponseMismatchAnalyzer.analyze(actual, EXPECTED, PROVIDER_WRONG);

        assertEquals(ResponseDiffResult.Kind.TYPE_MISMATCH, diff.primaryKind());
        assertTrue(diff.typeCoercions().containsKey("amount"));
        assertEquals("RESPONSE_TYPE_COERCE", diff.toTransformationRules().get("type"));
    }

    @Test
    void validResponse_noDiff() {
        Map<String, Object> actual = new LinkedHashMap<>(EXPECTED);
        ResponseDiffResult diff = ResponseMismatchAnalyzer.analyze(actual, EXPECTED, PROVIDER_WRONG);
        assertFalse(diff.hasDeterministicRule());
    }
}
