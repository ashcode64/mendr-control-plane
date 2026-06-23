package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransformationRulesParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesObjectWithMappingsMap() throws Exception {
        var node = mapper.readTree("""
            {
              "type": "FIELD_RENAME",
              "mappings": {
                "customer_id": "customerId",
                "total_amount": "amount"
              }
            }
            """);

        Map<String, Object> rules = TransformationRulesParser.parse(node, mapper);

        assertEquals("FIELD_RENAME", rules.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, String> mappings = (Map<String, String>) rules.get("mappings");
        assertEquals("customerId", mappings.get("customer_id"));
        assertEquals("amount", mappings.get("total_amount"));
    }

    @Test
    void mergesArrayOfRulesIntoSingleFieldRename() throws Exception {
        var node = mapper.readTree("""
            [
              { "type": "FIELD_RENAME", "mappings": { "customer_id": "customerId" } },
              { "type": "FIELD_RENAME", "mappings": { "total_amount": "amount" } }
            ]
            """);

        Map<String, Object> rules = TransformationRulesParser.parse(node, mapper);

        assertEquals("FIELD_RENAME", rules.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, String> mappings = (Map<String, String>) rules.get("mappings");
        assertEquals(2, mappings.size());
        assertEquals("customerId", mappings.get("customer_id"));
        assertEquals("amount", mappings.get("total_amount"));
    }

    @Test
    void flattensArrayStyleMappings() throws Exception {
        var node = mapper.readTree("""
            {
              "type": "FIELD_RENAME",
              "mappings": [
                { "from": "customer_id", "to": "customerId" },
                { "from": "card_token", "to": "cardToken" }
              ]
            }
            """);

        Map<String, Object> rules = TransformationRulesParser.parse(node, mapper);

        @SuppressWarnings("unchecked")
        Map<String, String> mappings = (Map<String, String>) rules.get("mappings");
        assertEquals("customerId", mappings.get("customer_id"));
        assertEquals("cardToken", mappings.get("card_token"));
    }

    @Test
    void preservesNumericDefaults() throws Exception {
        var node = mapper.readTree("""
            {
              "type": "ADD_DEFAULT",
              "defaults": {
                "amount": 99.99
              }
            }
            """);

        Map<String, Object> rules = TransformationRulesParser.parse(node, mapper);

        assertEquals("ADD_DEFAULT", rules.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) rules.get("defaults");
        assertEquals(99.99, defaults.get("amount"));
    }

    @Test
    void normalizesEndpointPath() throws Exception {
        var node = mapper.readTree("""
            {
              "type": "CORS_ORIGIN_OVERRIDE",
              "endpoint": "POST /api/payments/process",
              "callerOrigin": "http://a:1",
              "outboundOrigin": "http://b:2"
            }
            """);

        Map<String, Object> rules = TransformationRulesParser.parse(node, mapper);

        assertEquals("/api/payments/process", rules.get("endpoint"));
    }
}
