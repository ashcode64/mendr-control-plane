package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.service.context.CorsContext;
import com.selfhealing.analysis.service.context.DeterministicFinding;
import com.selfhealing.analysis.service.context.RoutingContext;
import com.selfhealing.analysis.service.context.StructuredFailureContext;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock analysis used when no API key is set (dev/demo). Produces the SAME typed
 * {@link AnalysisToolResult} the real tool path returns — one shape, two producers —
 * built from the structured context rather than scraping a flat prompt string.
 */
public final class MockAnalysis {

    private MockAnalysis() {}

    public static AnalysisToolResult build(StructuredFailureContext sc, FailureAnalysisContext ctx, String model) {
        // Prefer the deterministic finding: identical decision the real Tier-1 path forces.
        DeterministicFinding finding = sc.deterministicFinding();
        if (finding != null && finding.hasConfidentMatch() && finding.structuredDiff() != null) {
            Map<String, Object> rules = new LinkedHashMap<>(finding.structuredDiff());
            String ruleType = String.valueOf(rules.get("type"));
            return result(ruleType, rules,
                    "Deterministic " + finding.kind() + ": " + finding.summary(),
                    0.93, "Align the contract at the source service and remove this temporary rule.", model);
        }

        String category = sc.category();
        return switch (category == null ? "UNKNOWN" : category) {
            case "ROUTING" -> mockRouting(sc, ctx, model);
            case "CORS_UPSTREAM" -> mockCorsUpstream(sc, ctx, model);
            case "CORS" -> mockCorsEdge(sc, model);
            case "RESPONSE_MISMATCH" -> mockResponse(model);
            default -> mockSchema(model);
        };
    }

    private static AnalysisToolResult mockRouting(StructuredFailureContext sc, FailureAnalysisContext ctx, String model) {
        ApiFailureEvent event = ctx.event();
        RoutingContext routing = sc.routing();
        String serviceName = event.getServiceB();
        String attempted = routing != null ? routing.attemptedUrl() : event.getAttemptedUrl();
        String registered = event.getRegisteredBaseUrl();
        String probed = firstReachableProbe(routing);

        String suggested;
        String method;
        String rootCause;
        if (registered != null && attempted != null) {
            suggested = RoutingUrlResolver.mergeHostFromAttemptedPortFromRegistry(attempted, registered);
            method = "REGISTRY_LOOKUP";
            rootCause = String.format("Routing mismatch: attempted '%s' but registry has '%s' for '%s'.",
                    attempted, registered, serviceName);
        } else if (probed != null) {
            suggested = probed;
            method = "DNS_PROBE";
            rootCause = String.format("Service '%s' unreachable at '%s'. DNS probe found '%s'.",
                    serviceName, attempted, probed);
        } else {
            suggested = attempted != null ? attempted.replaceAll(":(\\d+)", ":8091") : "http://unknown-new-host:8091";
            method = "AI_SUGGESTED";
            rootCause = String.format("Service '%s' unreachable at '%s'. No registry/probe; suggesting port correction.",
                    serviceName, attempted);
        }

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("type", "ROUTING_OVERRIDE");
        rules.put("serviceName", serviceName);
        rules.put("originalUrl", attempted);
        rules.put("suggestedNewUrl", suggested != null ? RoutingUrlResolver.stripToBaseUrl(suggested) : null);
        rules.put("discoveryMethod", method);
        return result("ROUTING_OVERRIDE", rules, rootCause, 0.91,
                "Align gateway routing with the Mendr service registry for '" + serviceName + "'.", model);
    }

    private static AnalysisToolResult mockCorsUpstream(StructuredFailureContext sc, FailureAnalysisContext ctx, String model) {
        ApiFailureEvent event = ctx.event();
        CorsContext cors = sc.cors();
        String callerOrigin = cors != null ? cors.requestOrigin() : event.getRequestOrigin();
        String outbound = ctx.corsUpstreamDiff().hasDeterministicRule()
                ? ctx.corsUpstreamDiff().outboundOrigin()
                : firstOrigin(cors != null ? cors.upstreamAllowedOrigins() : null);
        if (outbound == null) outbound = "http://localhost:8090";

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("type", "CORS_ORIGIN_OVERRIDE");
        rules.put("sourceService", event.getServiceA());
        rules.put("targetService", event.getServiceB());
        rules.put("endpoint", EndpointNormalizer.normalize(event.getEndpoint()));
        rules.put("callerOrigin", callerOrigin);
        rules.put("outboundOrigin", outbound);
        rules.put("rewriteResponseAcao", true);
        return result("CORS_ORIGIN_OVERRIDE", rules,
                String.format("Service B rejected real Origin '%s'; rewrite outbound Origin to '%s' until B is updated.",
                        callerOrigin, outbound),
                0.93, "Add '" + callerOrigin + "' to Service B's CORS allowlist and remove this override.", model);
    }

    private static AnalysisToolResult mockCorsEdge(StructuredFailureContext sc, String model) {
        CorsContext cors = sc.cors();
        String origin = cors != null ? cors.requestOrigin() : null;
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("type", "CORS_ALLOW");
        rules.put("targetService", null);
        rules.put("newOrigin", origin);
        rules.put("previousOrigin", null);
        rules.put("allowedMethods", "GET,POST,PUT,DELETE,PATCH,OPTIONS");
        rules.put("allowedHeaders", "*");
        return result("CORS_ALLOW", rules,
                String.format("Origin '%s' is not in Mendr's edge allowlist for this target.", origin),
                0.92, "Add '" + origin + "' to the target's CORS allowed-origins and remove this temporary rule.", model);
    }

    private static AnalysisToolResult mockResponse(String model) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("type", "RESPONSE_FIELD_RENAME");
        rules.put("mappings", Map.of());
        return result("RESPONSE_FIELD_RENAME", rules,
                "Response shape does not match the caller's expected contract.",
                0.6, "Align the response contract between provider and caller.", model);
    }

    private static AnalysisToolResult mockSchema(String model) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("type", "FIELD_RENAME");
        rules.put("mappings", Map.of());
        return result("FIELD_RENAME", rules,
                "Schema mismatch detected: the request payload does not match the target contract.",
                0.6, "Review and align API contracts; add contract testing to CI.", model);
    }

    private static String firstReachableProbe(RoutingContext routing) {
        if (routing == null || routing.recentProbes() == null) return null;
        for (RoutingContext.DnsProbeSummary p : routing.recentProbes()) {
            if (p.reachable() && p.probedUrl() != null) return p.probedUrl();
        }
        return null;
    }

    private static String firstOrigin(List<String> origins) {
        return (origins == null || origins.isEmpty()) ? null : origins.get(0);
    }

    private static AnalysisToolResult result(String ruleType, Map<String, Object> rules,
                                             String rootCause, double confidence, String fix, String model) {
        return new AnalysisToolResult(AnalysisToolResult.Source.MOCK, model, ruleType, rules,
                rootCause, confidence, fix);
    }
}
