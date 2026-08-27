package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMismatchAnalyzerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> receiver = Map.of(
            "customerId", "CUST-123",
            "customerEmail", "alice@example.com",
            "orderRef", "ORD-1001",
            "amount", 99.99,
            "currency", "USD",
            "cardToken", "tok_abc");

    private final Map<String, Object> senderSnake = Map.of(
            "customer_id", "CUST-123",
            "customer_email", "alice@example.com",
            "order_ref", "ORD-1001",
            "total_amount", 99.99,
            "currency_code", "USD",
            "card_token", "tok_abc");

    private final Map<String, Object> senderCamel = Map.of(
            "customerId", "CUST-123",
            "customerEmail", "alice@example.com",
            "orderRef", "ORD-1001",
            "amount", 99.99,
            "currency", "USD",
            "cardToken", "tok_abc");

    private final Map<String, Object> snakeActual = Map.of(
            "customer_id", "CUST-002",
            "customer_email", "bob@example.com",
            "order_ref", "ORD-BAD",
            "total_amount", 99.99,
            "currency_code", "USD",
            "card_token", "tok_bad");

    @Test
    void detectsMissingFieldWhenCountLowerThanReceiver() {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("customerId", "CUST-001");
        actual.put("customerEmail", "alice@example.com");
        actual.put("orderRef", "ORD-MISSING-1001");
        actual.put("currency", "USD");
        actual.put("cardToken", "tok_xyz");

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, senderSnake, receiver,
                "400 Bad Request: amount is required",
                Map.of("violations", java.util.List.of("amount is required")));

        assertEquals(SchemaDiffResult.Kind.MISSING_FIELD, diff.kind());
        assertTrue(diff.missingFields().contains("amount"));
        assertEquals("ADD_DEFAULT", diff.toTransformationRules().get("type"));
        assertEquals(99.99, ((Map<?, ?>) diff.toTransformationRules().get("defaults")).get("amount"));
        assertTrue(diff.hasDeterministicRule());
    }

    @Test
    void detectsRenameWhenSnakeCaseSenderContract() {
        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                snakeActual, senderSnake, receiver, "customerId is required", null);

        assertEquals(SchemaDiffResult.Kind.FIELD_RENAME, diff.kind());
        assertEquals("FIELD_RENAME", diff.toTransformationRules().get("type"));
        assertEquals("customerId", diff.renameMappings().get("customer_id"));
        assertEquals("amount", diff.renameMappings().get("total_amount"));
        assertTrue(diff.hasDeterministicRule());
    }

    @Test
    void detectsRenameWhenCamelCaseSenderContractAndSnakeActual() {
        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                snakeActual, senderCamel, receiver,
                "{\"error\":\"SCHEMA_VALIDATION_FAILED\",\"violations\":[\"customerEmail is required\",\"orderRef is required\",\"customerId is required\"]}",
                null);

        assertEquals(SchemaDiffResult.Kind.FIELD_RENAME, diff.kind());
        Map<?, ?> mappings = (Map<?, ?>) diff.toTransformationRules().get("mappings");
        assertEquals(6, mappings.size());
        assertEquals("customerId", mappings.get("customer_id"));
        assertEquals("customerEmail", mappings.get("customer_email"));
        assertEquals("orderRef", mappings.get("order_ref"));
        assertEquals("amount", mappings.get("total_amount"));
        assertEquals("currency", mappings.get("currency_code"));
        assertEquals("cardToken", mappings.get("card_token"));
    }

    @Test
    void detectsRenameWhenReceiverContractIsJsonString() {
        String receiverJson = """
                {"customerId":"CUST-123","customerEmail":"alice@example.com","orderRef":"ORD-1001",\
                "amount":99.99,"currency":"USD","cardToken":"tok_abc"}""";

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                snakeActual, senderCamel, receiverJson, "customerId is required", null);

        assertEquals(SchemaDiffResult.Kind.FIELD_RENAME, diff.kind());
        assertFalse(diff.renameMappings().isEmpty());
    }

    @Test
    void contractPayloadParserParsesJsonString() {
        String json = "{\"customerId\":\"CUST-123\",\"amount\":99.99}";
        Map<String, Object> parsed = ContractPayloadParser.toMap(json, MAPPER);
        assertEquals("CUST-123", parsed.get("customerId"));
        assertEquals(99.99, parsed.get("amount"));
    }

    @Test
    void detectsTypeMismatchWhenAmountIsString() {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("customerId", "CUST-001");
        actual.put("customerEmail", "alice@example.com");
        actual.put("orderRef", "ORD-1");
        actual.put("amount", "1999.98");
        actual.put("currency", "USD");
        actual.put("cardToken", "tok_abc");

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, senderSnake, receiver, "JSON type mismatch", null);

        assertEquals(SchemaDiffResult.Kind.TYPE_MISMATCH, diff.kind());
        assertEquals("TYPE_COERCE", diff.toTransformationRules().get("type"));
        assertEquals("double", diff.typeCoercions().get("amount"));
    }

    @Test
    void schemaMarksOptionalFieldAbsenceAsNotMissing() {
        // receiver example has 4 fields, but schema says only customerId + amount are required.
        Map<String, Object> receiverEx = Map.of(
                "customerId", "C1", "amount", 99.99, "note", "n", "coupon", "x");
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("customerId", "C1");
        actual.put("amount", 99.99);
        // note + coupon absent — but they are optional per schema

        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", java.util.List.of("customerId", "amount"),
                "properties", Map.of());

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, receiverEx, receiverEx, schema, "", null);

        assertEquals(SchemaDiffResult.Kind.NONE, diff.kind(),
                "optional fields absent must not produce a MISSING_FIELD rule");
        assertFalse(diff.hasDeterministicRule());
    }

    @Test
    void schemaStillFlagsRequiredFieldAbsence() {
        Map<String, Object> receiverEx = Map.of(
                "customerId", "C1", "amount", 99.99, "note", "n");
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("customerId", "C1");
        actual.put("note", "n");
        // amount (required) absent

        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", java.util.List.of("customerId", "amount"),
                "properties", Map.of());

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, receiverEx, receiverEx, schema, "amount is required", null);

        assertEquals(SchemaDiffResult.Kind.MISSING_FIELD, diff.kind());
        assertTrue(diff.missingFields().contains("amount"));
        assertTrue(diff.hasDeterministicRule());
    }

    @Test
    void detectsMoveWhenIdentityFieldIsNestedTooDeep() {
        // actual nests token under credentials; receiver wants it at the top level.
        Map<String, Object> recv = Map.of("token", "JWT");
        Map<String, Object> actual = Map.of("credentials", Map.of("token", "JWT"));

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, null, recv, "token is required", null);

        assertEquals(SchemaDiffResult.Kind.FIELD_MOVE, diff.kind());
        assertTrue(diff.hasDeterministicRule());
        Map<String, Object> rules = diff.toTransformationRules();
        assertEquals("FIELD_MOVE", rules.get("type"));
        java.util.List<?> moves = (java.util.List<?>) rules.get("moves");
        assertEquals(1, moves.size());
        Map<?, ?> mv = (Map<?, ?>) moves.get(0);
        assertEquals("/credentials/token", mv.get("from"));
        assertEquals("/token", mv.get("to"));
        assertEquals(false, mv.get("copy"));
    }

    @Test
    void detectsMoveWhenReceiverExpectsDeeperNesting() {
        // actual has flat user_id; receiver wants it nested under user_obj.
        Map<String, Object> recv = new LinkedHashMap<>();
        recv.put("user_obj", Map.of("user_id", 7));
        recv.put("amount_cents", 10);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("user_id", 7);
        actual.put("amount_cents", 10);

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, null, recv, "user_obj.user_id is required", null);

        assertEquals(SchemaDiffResult.Kind.FIELD_MOVE, diff.kind());
        java.util.List<?> moves = (java.util.List<?>) diff.toTransformationRules().get("moves");
        assertEquals(1, moves.size());
        Map<?, ?> mv = (Map<?, ?>) moves.get(0);
        assertEquals("/user_id", mv.get("from"));
        assertEquals("/user_obj/user_id", mv.get("to"));
    }

    @Test
    void doesNotProposeMoveForPlainTopLevelRename() {
        // same depth, different name → FIELD_RENAME, never FIELD_MOVE.
        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                snakeActual, senderSnake, receiver, "customerId is required", null);
        assertEquals(SchemaDiffResult.Kind.FIELD_RENAME, diff.kind());
    }

    @Test
    void emptyAddDefaultIsNotDeterministic() {
        SchemaDiffResult diff = new SchemaDiffResult(
                SchemaDiffResult.Kind.MISSING_FIELD,
                "test",
                java.util.Set.of("customerId"),
                Map.of(),
                Map.of(),
                Map.of(),
                java.util.List.of(),
                true,
                java.util.List.of(),
                null,
                false);
        assertFalse(diff.hasDeterministicRule());
        assertTrue(diff.toTransformationRules().containsKey("defaults"));
    }
}
