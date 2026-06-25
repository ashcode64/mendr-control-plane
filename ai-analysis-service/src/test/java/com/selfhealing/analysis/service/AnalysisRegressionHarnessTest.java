package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.service.context.StructuredContextAssembler;
import com.selfhealing.analysis.service.context.StructuredFailureContext;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;
import com.selfhealing.analysis.service.tool.AnalysisTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression corpus: a fixed set of representative failures (seeded from the demo
 * scenarios) paired with the rule each one SHOULD produce. Runs the full non-API
 * decision path — {@link StructuredContextAssembler} then {@link MockAnalysis} —
 * so a change to the structured-context shape, the deterministic finding flow, or
 * the tool/rule-type mapping that silently makes analysis worse fails here.
 *
 * <p>The mock path mirrors the real Tier-1 forced-tool decision (it prefers the
 * deterministic finding), so these assertions also guard the real path's rule-type
 * selection without needing an Anthropic key.
 */
class AnalysisRegressionHarnessTest {

    private static final String MODEL = "test-model";

    private record Scenario(
            String name,
            FailureAnalysisContext ctx,
            String expectedRuleType,
            Function<Map<String, Object>, Void> extraAssertions) {
    }

    @Test
    @DisplayName("regression corpus: each seeded failure produces its expected rule type")
    void corpusProducesExpectedRules() {
        for (Scenario s : corpus()) {
            StructuredFailureContext structured = StructuredContextAssembler.assemble(s.ctx());
            AnalysisToolResult result = MockAnalysis.build(structured, s.ctx(), MODEL);

            assertNotNull(result.ruleType(), s.name() + ": rule type must not be null");
            assertEquals(s.expectedRuleType(), result.ruleType(), s.name() + ": rule type");
            assertEquals(s.expectedRuleType(), result.transformationRules().get("type"),
                    s.name() + ": transformationRules.type");
            assertNotNull(AnalysisTools.toolForRuleType(result.ruleType()),
                    s.name() + ": rule type must map to a known tool");
            if (s.extraAssertions() != null) {
                s.extraAssertions().apply(result.transformationRules());
            }
        }
    }

    @Test
    @DisplayName("every category routes to a defined tool set")
    void everyCategoryHasTools() {
        for (String category : List.of(
                "SCHEMA_MISMATCH", "RESPONSE_MISMATCH", "ROUTING", "CORS", "CORS_UPSTREAM", "UNKNOWN")) {
            assertTrue(AnalysisTools.toolsForCategory(category).size() >= 1,
                    category + " must have at least one tool");
        }
    }

    // ── Corpus ───────────────────────────────────────────────────────────────────

    private List<Scenario> corpus() {
        return List.of(
                schemaFieldRename(),
                schemaMissingField(),
                schemaTypeCoerce(),
                routing(),
                corsEdge(),
                corsUpstream());
    }

    private Scenario schemaFieldRename() {
        Map<String, Object> receiver = Map.of(
                "customerId", "C1", "amount", 10.0, "currency", "USD");
        Map<String, Object> sender = receiver;
        Map<String, Object> actual = Map.of(
                "customer_id", "C1", "total_amount", 10.0, "currency_code", "USD");

        ApiFailureEvent event = baseEvent("SCHEMA_MISMATCH")
                .requestPayload(actual)
                .errorMessage("customerId is required")
                .build();

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, sender, receiver, "customerId is required", null);
        FailureAnalysisContext ctx = ctxWithSchemaDiff(event, diff, sender, receiver);

        return new Scenario("schema-field-rename", ctx, "FIELD_RENAME", rules -> {
            assertTrue(rules.get("mappings") instanceof Map<?, ?> m && !m.isEmpty(),
                    "field rename must carry mappings");
            return null;
        });
    }

    private Scenario schemaMissingField() {
        Map<String, Object> receiver = Map.of(
                "customerId", "C1", "amount", 99.99, "currency", "USD", "cardToken", "t");
        Map<String, Object> sender = receiver;
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("customerId", "C1");
        actual.put("currency", "USD");
        actual.put("cardToken", "t");

        ApiFailureEvent event = baseEvent("SCHEMA_MISMATCH")
                .requestPayload(actual)
                .errorMessage("400 Bad Request: amount is required")
                .build();

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, sender, receiver, "400 Bad Request: amount is required",
                Map.of("violations", List.of("amount is required")));
        FailureAnalysisContext ctx = ctxWithSchemaDiff(event, diff, sender, receiver);

        return new Scenario("schema-missing-field", ctx, "ADD_DEFAULT", rules -> {
            assertTrue(rules.get("defaults") instanceof Map<?, ?> m && m.containsKey("amount"),
                    "add default must supply amount");
            return null;
        });
    }

    private Scenario schemaTypeCoerce() {
        Map<String, Object> receiver = Map.of(
                "customerId", "C1", "amount", 10.0, "currency", "USD", "cardToken", "t", "orderRef", "o", "customerEmail", "e");
        Map<String, Object> sender = receiver;
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("customerId", "C1");
        actual.put("amount", "10.0");
        actual.put("currency", "USD");
        actual.put("cardToken", "t");
        actual.put("orderRef", "o");
        actual.put("customerEmail", "e");

        ApiFailureEvent event = baseEvent("SCHEMA_MISMATCH")
                .requestPayload(actual)
                .errorMessage("JSON type mismatch")
                .build();

        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, sender, receiver, "JSON type mismatch", null);
        FailureAnalysisContext ctx = ctxWithSchemaDiff(event, diff, sender, receiver);

        return new Scenario("schema-type-coerce", ctx, "TYPE_COERCE", rules -> {
            assertTrue(rules.get("coercions") instanceof Map<?, ?> m && m.containsKey("amount"),
                    "type coerce must carry coercions");
            return null;
        });
    }

    private Scenario routing() {
        ApiFailureEvent event = baseEvent("ROUTING")
                .attemptedUrl("http://payment-service:8092")
                .registeredBaseUrl("http://payment-service:8091")
                .build();
        FailureAnalysisContext ctx = new FailureAnalysisContext(
                event, "ROUTING",
                new ContractContext(null, null, null, null),
                new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of()),
                CorsPolicyContext.empty(),
                List.of(), List.of(), List.of(),
                SchemaDiffResult.empty(), ResponseDiffResult.empty(),
                CorsUpstreamDiffResult.empty(), CorsEdgeDiffResult.empty(), null);

        return new Scenario("routing-port-correction", ctx, "ROUTING_OVERRIDE", rules -> {
            assertEquals("8091", String.valueOf(rules.get("suggestedNewUrl")).replaceAll(".*:", ""),
                    "routing must point at the registry port");
            return null;
        });
    }

    private Scenario corsEdge() {
        ApiFailureEvent event = baseEvent("CORS")
                .requestOrigin("http://order-service-v2:9090")
                .corsBlockedAt("EDGE")
                .build();
        FailureAnalysisContext ctx = new FailureAnalysisContext(
                event, "CORS",
                new ContractContext(null, null, null, null),
                new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of()),
                CorsPolicyContext.empty(),
                List.of(), List.of("http://order-service:8090"), List.of(),
                SchemaDiffResult.empty(), ResponseDiffResult.empty(),
                CorsUpstreamDiffResult.empty(), CorsEdgeDiffResult.empty(), null);

        return new Scenario("cors-edge-allow", ctx, "CORS_ALLOW", rules -> {
            assertEquals("http://order-service-v2:9090", rules.get("newOrigin"),
                    "cors allow newOrigin must equal blocked origin");
            return null;
        });
    }

    private Scenario corsUpstream() {
        ApiFailureEvent event = baseEvent("CORS_UPSTREAM")
                .requestOrigin("http://order-service-v2:9090")
                .corsBlockedAt("UPSTREAM")
                .build();

        CorsUpstreamDiffResult diff = new CorsUpstreamDiffResult(
                "upstream rejected origin",
                "http://order-service-v2:9090",
                "http://localhost:8090",
                "order-service", "payment-service", "/payments", true);

        FailureAnalysisContext ctx = new FailureAnalysisContext(
                event, "CORS_UPSTREAM",
                new ContractContext(null, null, null, null),
                new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of()),
                CorsPolicyContext.empty(),
                List.of("http://localhost:8090"), List.of(), List.of(),
                SchemaDiffResult.empty(), ResponseDiffResult.empty(),
                diff, CorsEdgeDiffResult.empty(), null);

        return new Scenario("cors-upstream-origin-override", ctx, "CORS_ORIGIN_OVERRIDE", rules -> {
            assertEquals("http://order-service-v2:9090", rules.get("callerOrigin"));
            assertEquals("http://localhost:8090", rules.get("outboundOrigin"));
            return null;
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private ApiFailureEvent.ApiFailureEventBuilder baseEvent(String category) {
        return ApiFailureEvent.builder()
                .failureId(UUID.randomUUID())
                .serviceA("order-service")
                .serviceB("payment-service")
                .endpoint("/payments")
                .httpMethod("POST")
                .failureCategory(category);
    }

    private FailureAnalysisContext ctxWithSchemaDiff(
            ApiFailureEvent event, SchemaDiffResult diff, Object sender, Object receiver) {
        return new FailureAnalysisContext(
                event, "SCHEMA_MISMATCH",
                new ContractContext(sender, receiver, null, null),
                new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of()),
                CorsPolicyContext.empty(),
                List.of(), List.of(), List.of(),
                diff, ResponseDiffResult.empty(),
                CorsUpstreamDiffResult.empty(), CorsEdgeDiffResult.empty(), null);
    }
}
