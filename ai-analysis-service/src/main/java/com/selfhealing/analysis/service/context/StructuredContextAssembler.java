package com.selfhealing.analysis.service.context;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.service.ActiveRuleSummary;
import com.selfhealing.analysis.service.ContractContext;
import com.selfhealing.analysis.service.CorsEdgeDiffResult;
import com.selfhealing.analysis.service.CorsUpstreamDiffResult;
import com.selfhealing.analysis.service.FailureAnalysisContext;
import com.selfhealing.analysis.service.RegistryDiscoveryContext;
import com.selfhealing.analysis.service.ResponseDiffResult;
import com.selfhealing.analysis.service.SchemaDiffResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps the assembled {@link FailureAnalysisContext} into a category-scoped
 * {@link StructuredFailureContext}. Sections irrelevant to the category are left
 * null and dropped on serialization — no separate "which fields does this category
 * need" logic beyond what {@code enrich()} already gated.
 */
public final class StructuredContextAssembler {

    private StructuredContextAssembler() {}

    public static StructuredFailureContext assemble(FailureAnalysisContext ctx) {
        ApiFailureEvent event = ctx.event();
        String category = ctx.category();

        return new StructuredFailureContext(
                event.getFailureId() != null ? event.getFailureId().toString() : null,
                category,
                event.getHttpMethod(),
                event.getEndpoint(),
                event.getErrorCode() != 0 ? event.getErrorCode() : null,
                event.getErrorMessage(),
                routingFor(category, ctx),
                corsFor(category, ctx),
                schemaFor(category, ctx),
                topologyFor(ctx),
                deterministicFor(category, ctx),
                priorAttemptsFor(ctx));
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    private static RoutingContext routingFor(String category, FailureAnalysisContext ctx) {
        boolean wanted = "ROUTING".equals(category) || "CORS_UPSTREAM".equals(category);
        if (!wanted) return null;

        ApiFailureEvent event = ctx.event();
        RegistryDiscoveryContext registry = ctx.registry();

        return new RoutingContext(
                event.getServiceA(),
                event.getServiceB(),
                event.getEndpoint(),
                event.getRegisteredBaseUrl(),
                event.getTargetServiceUrl(),
                event.getAttemptedUrl(),
                event.getDnsProbeDiscoveryUrl(),
                nullIfEmpty(probes(registry.recentDnsProbes())),
                nullIfEmpty(services(registry.involvedServices())),
                "ROUTING".equals(category) ? nullIfEmpty(services(registry.allActiveServices())) : null,
                nullIfEmpty(routingOverrides(registry.activeRoutingRules())));
    }

    private static List<RoutingContext.DnsProbeSummary> probes(List<Map<String, Object>> rows) {
        List<RoutingContext.DnsProbeSummary> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new RoutingContext.DnsProbeSummary(
                    str(r.get("probed_url")),
                    Boolean.TRUE.equals(r.get("reachable")),
                    r.get("http_status")));
        }
        return out;
    }

    private static List<RoutingContext.RegisteredService> services(List<Map<String, Object>> rows) {
        List<RoutingContext.RegisteredService> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new RoutingContext.RegisteredService(
                    str(r.get("name")),
                    str(r.get("base_url")),
                    r.get("last_health_status"),
                    str(r.get("namespace"))));
        }
        return out;
    }

    private static List<RoutingContext.RoutingOverrideSummary> routingOverrides(List<Map<String, Object>> rows) {
        List<RoutingContext.RoutingOverrideSummary> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new RoutingContext.RoutingOverrideSummary(
                    str(r.get("service_name")),
                    str(r.get("original_url")),
                    str(r.get("new_url")),
                    str(r.get("discovery_method"))));
        }
        return out;
    }

    // ── CORS ─────────────────────────────────────────────────────────────────

    private static CorsContext corsFor(String category, FailureAnalysisContext ctx) {
        boolean wanted = "CORS".equals(category) || "CORS_UPSTREAM".equals(category);
        if (!wanted) return null;

        ApiFailureEvent event = ctx.event();
        boolean upstream = "CORS_UPSTREAM".equals(category);

        return new CorsContext(
                event.getRequestOrigin(),
                event.getCorsBlockedAt() != null ? event.getCorsBlockedAt() : (upstream ? "UPSTREAM" : "EDGE"),
                nullIfEmpty(ctx.mendrEdgeAllowedOrigins()),
                upstream ? nullIfEmpty(ctx.upstreamAllowedOrigins()) : null,
                event.getUpstreamOriginSent(),
                upstream ? ctx.corsPolicy().allowedCallerOrigin() : null,
                upstream ? event.getRequestPayload() : null,
                upstream ? event.getResponsePayload() : null);
    }

    // ── Schema ───────────────────────────────────────────────────────────────

    private static SchemaContext schemaFor(String category, FailureAnalysisContext ctx) {
        boolean wanted = "SCHEMA_MISMATCH".equals(category) || "RESPONSE_MISMATCH".equals(category);
        if (!wanted) return null;

        ApiFailureEvent event = ctx.event();
        ContractContext contracts = ctx.contracts();
        String description = ctx.topology() != null && ctx.topology().failingCall() != null
                ? ctx.topology().failingCall().description()
                : null;

        return new SchemaContext(
                contracts.senderContract(),
                contracts.receiverContract(),
                contracts.callerResponseContract(),
                contracts.providerResponseContract(),
                nullIfEmptyMap(event.getRequestPayload()),
                nullIfEmptyMap(event.getResponsePayload()),
                description);
    }

    // ── Topology ─────────────────────────────────────────────────────────────

    private static TopologyContext topologyFor(FailureAnalysisContext ctx) {
        TopologyContext topo = ctx.topology();
        return (topo == null || topo.isEmpty()) ? null : topo;
    }

    // ── Deterministic finding ──────────────────────────────────────────────────

    private static DeterministicFinding deterministicFor(String category, FailureAnalysisContext ctx) {
        switch (category) {
            case "SCHEMA_MISMATCH" -> {
                SchemaDiffResult d = ctx.schemaDiff();
                if (d.hasDeterministicRule()) {
                    return new DeterministicFinding(true, d.kind().name(), d.summary(), d.toTransformationRules());
                }
            }
            case "RESPONSE_MISMATCH" -> {
                ResponseDiffResult d = ctx.responseDiff();
                if (d.hasDeterministicRule()) {
                    return new DeterministicFinding(true, d.primaryKind().name(), d.summary(), d.toTransformationRules());
                }
            }
            case "CORS_UPSTREAM" -> {
                CorsUpstreamDiffResult d = ctx.corsUpstreamDiff();
                if (d.hasDeterministicRule()) {
                    return new DeterministicFinding(true, "CORS_ORIGIN_OVERRIDE", d.summary(), d.toTransformationRules());
                }
            }
            case "CORS" -> {
                CorsEdgeDiffResult d = ctx.corsEdgeDiff();
                if (d.hasDeterministicRule()) {
                    return new DeterministicFinding(true, "CORS_ALLOW", d.summary(), d.toTransformationRules());
                }
            }
            default -> { }
        }
        return DeterministicFinding.none();
    }

    // ── Prior attempts ─────────────────────────────────────────────────────────

    private static List<PriorAttempt> priorAttemptsFor(FailureAnalysisContext ctx) {
        List<ActiveRuleSummary> rules = ctx.activeRulesOnRoute();
        if (rules == null || rules.isEmpty()) return null;
        List<PriorAttempt> out = new ArrayList<>();
        for (ActiveRuleSummary r : rules) {
            out.add(new PriorAttempt(
                    r.ruleType(),
                    r.scope(),
                    r.appliedAt(),
                    r.ruleDefinition(),
                    r.failureRecurredAfterThisRule()));
        }
        return out;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static <T> List<T> nullIfEmpty(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }

    private static Map<String, Object> nullIfEmptyMap(Map<String, Object> map) {
        return (map == null || map.isEmpty()) ? null : map;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
