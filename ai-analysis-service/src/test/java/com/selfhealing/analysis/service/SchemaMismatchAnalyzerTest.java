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
    void emptyAddDefaultIsNotDeterministic() {
        SchemaDiffResult diff = new SchemaDiffResult(
                SchemaDiffResult.Kind.MISSING_FIELD,
                "test",
                java.util.Set.of("customerId"),
                Map.of(),
                Map.of(),
                Map.of(),
                true);
        assertFalse(diff.hasDeterministicRule());
        assertTrue(diff.toTransformationRules().containsKey("defaults"));
    }
}
