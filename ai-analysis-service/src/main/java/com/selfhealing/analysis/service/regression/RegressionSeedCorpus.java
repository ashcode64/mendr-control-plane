package com.selfhealing.analysis.service.regression;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.service.ContractContext;
import com.selfhealing.analysis.service.CorsEdgeDiffResult;
import com.selfhealing.analysis.service.CorsPolicyContext;
import com.selfhealing.analysis.service.CorsUpstreamDiffResult;
import com.selfhealing.analysis.service.FailureAnalysisContext;
import com.selfhealing.analysis.service.RegistryDiscoveryContext;
import com.selfhealing.analysis.service.ResponseDiffResult;
import com.selfhealing.analysis.service.SchemaDiffResult;
import com.selfhealing.analysis.service.SchemaMismatchAnalyzer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared seeded regression corpus for {@link RegressionHarnessService} and tests.
 */
public final class RegressionSeedCorpus {

    private RegressionSeedCorpus() {}

    public record Scenario(String name, FailureAnalysisContext ctx, String expectedRuleType) {}

    public static List<Scenario> scenarios() {
        return List.of(
                schemaFieldRename(),
                schemaMissingField(),
                schemaTypeCoerce(),
                routing(),
                corsEdge(),
                corsUpstream());
    }

    private static Scenario schemaFieldRename() {
        Map<String, Object> receiver = Map.of(
                "customerId", "C1", "amount", 10.0, "currency", "USD");
        Map<String, Object> actual = Map.of(
                "customer_id", "C1", "total_amount", 10.0, "currency_code", "USD");
        ApiFailureEvent event = baseEvent("SCHEMA_MISMATCH")
                .requestPayload(actual)
                .errorMessage("customerId is required")
                .build();
        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, receiver, receiver, "customerId is required", null);
        return new Scenario("schema-field-rename", ctxWithSchemaDiff(event, diff), "FIELD_RENAME");
    }

    private static Scenario schemaMissingField() {
        Map<String, Object> receiver = Map.of(
                "customerId", "C1", "amount", 99.99, "currency", "USD", "cardToken", "t");
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("customerId", "C1");
        actual.put("currency", "USD");
        actual.put("cardToken", "t");
        ApiFailureEvent event = baseEvent("SCHEMA_MISMATCH")
                .requestPayload(actual)
                .errorMessage("400 Bad Request: amount is required")
                .build();
        SchemaDiffResult diff = SchemaMismatchAnalyzer.analyze(
                actual, receiver, receiver, "400 Bad Request: amount is required",
                Map.of("violations", List.of("amount is required")));
        return new Scenario("schema-missing-field", ctxWithSchemaDiff(event, diff), "ADD_DEFAULT");
    }

    private static Scenario schemaTypeCoerce() {
        Map<String, Object> receiver = Map.of(
                "customerId", "C1", "amount", 10.0, "currency", "USD", "cardToken", "t",
                "orderRef", "o", "customerEmail", "e");
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
                actual, receiver, receiver, "JSON type mismatch", null);
        return new Scenario("schema-type-coerce", ctxWithSchemaDiff(event, diff), "TYPE_COERCE");
    }

    private static Scenario routing() {
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
        return new Scenario("routing-port-correction", ctx, "ROUTING_OVERRIDE");
    }

    private static Scenario corsEdge() {
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
        return new Scenario("cors-edge-allow", ctx, "CORS_ALLOW");
    }

    private static Scenario corsUpstream() {
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
        return new Scenario("cors-upstream-origin-override", ctx, "CORS_ORIGIN_OVERRIDE");
    }

    private static ApiFailureEvent.ApiFailureEventBuilder baseEvent(String category) {
        return ApiFailureEvent.builder()
                .failureId(UUID.randomUUID())
                .serviceA("order-service")
                .serviceB("payment-service")
                .endpoint("/payments")
                .httpMethod("POST")
                .failureCategory(category);
    }

    private static FailureAnalysisContext ctxWithSchemaDiff(ApiFailureEvent event, SchemaDiffResult diff) {
        return new FailureAnalysisContext(
                event, "SCHEMA_MISMATCH",
                new ContractContext(null, null, null, null),
                new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of()),
                CorsPolicyContext.empty(),
                List.of(), List.of(), List.of(),
                diff, ResponseDiffResult.empty(),
                CorsUpstreamDiffResult.empty(), CorsEdgeDiffResult.empty(), null);
    }
}
