package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.RouteConfigSnapshot;
import com.selfhealing.gateway.dto.RouteConfigSnapshot.*;
import com.selfhealing.gateway.model.RateLimitPolicy;
import com.selfhealing.gateway.model.ServiceInstance;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.model.Tenant;
import com.selfhealing.gateway.repository.RateLimitPolicyRepository;
import com.selfhealing.gateway.repository.ServiceInstanceRepository;
import com.selfhealing.gateway.repository.ServiceRegistrationRepository;
import com.selfhealing.gateway.repository.TenantRepository;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Overlays enterprise traffic / rate-limit / auth / cache policies onto route snapshots.
 * Additive: never removes existing snapshot fields; only fills policy blocks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayPolicyOverlayService {

    private final ServiceRegistrationRepository serviceRegistrationRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final RateLimitPolicyRepository rateLimitPolicyRepository;
    private final TenantRepository tenantRepository;

    public void overlay(RouteConfigSnapshot snapshot, String targetService, String endpoint) {
        if (snapshot == null || targetService == null) {
            return;
        }
        Optional<ServiceRegistration> regOpt =
                serviceRegistrationRepository.findByNameAndIsActiveTrue(targetService);
        if (regOpt.isEmpty()) {
            return;
        }
        ServiceRegistration reg = regOpt.get();

        snapshot.setProtocol(reg.getProtocol() != null ? reg.getProtocol() : "HTTP");
        snapshot.setHealthEndpoint(reg.getHealthEndpoint() != null
                ? reg.getHealthEndpoint() : "/actuator/health");
        snapshot.setTargetInstances(toInstanceSnapshots(reg));
        snapshot.setTrafficPolicy(toTrafficPolicy(reg));
        snapshot.setCachePolicy(toCachePolicy(reg.getCachePolicyJson()));
        snapshot.setAuthPolicy(toAuthPolicy(reg.getAuthPolicyJson()));
        snapshot.setWafPolicy(extractWaf(reg));
        snapshot.setAiPolicy(extractAi(reg));
        snapshot.setRateLimitPolicy(resolveRateLimit(targetService, endpoint));
        snapshot.setTenantQuota(resolveTenantQuota());
        snapshot.setVersioning(extractVersioning(reg));
    }

    private TenantQuotaSnapshot resolveTenantQuota() {
        try {
            UUID tid = TenantContext.currentOrDefault();
            Optional<Tenant> tenant = tenantRepository.findById(tid);
            if (tenant.isEmpty()) return null;
            Tenant t = tenant.get();
            if (t.getQuotaRpm() == null && t.getQuotaRpd() == null) return null;
            return TenantQuotaSnapshot.builder()
                    .tenantId(tid.toString())
                    .quotaRpm(t.getQuotaRpm())
                    .quotaRpd(t.getQuotaRpd())
                    .build();
        } catch (Exception e) {
            log.debug("tenantQuota overlay skipped: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private WafPolicySnapshot extractWaf(ServiceRegistration reg) {
        Map<String, Object> json = reg.getAuthPolicyJson();
        if (json != null && json.get("waf") instanceof Map<?, ?> wafMap) {
            return toWafPolicy((Map<String, Object>) wafMap);
        }
        // Also allow top-level via cachePolicyJson sibling stored under retry_policy_json.waf
        Map<String, Object> retry = reg.getRetryPolicyJson();
        if (retry != null && retry.get("waf") instanceof Map<?, ?> wafMap) {
            return toWafPolicy((Map<String, Object>) wafMap);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private AiPolicySnapshot extractAi(ServiceRegistration reg) {
        Map<String, Object> json = reg.getAuthPolicyJson();
        if (json != null && json.get("ai") instanceof Map<?, ?> aiMap) {
            return toAiPolicy((Map<String, Object>) aiMap);
        }
        if ("AI".equalsIgnoreCase(reg.getProtocol())) {
            return AiPolicySnapshot.builder()
                    .tokensPerMinute(100000)
                    .requestsPerMinute(60)
                    .blockJailbreak(true)
                    .redactPii(true)
                    .semanticCacheEnabled(false)
                    .build();
        }
        return null;
    }

    private WafPolicySnapshot toWafPolicy(Map<String, Object> json) {
        if (json == null || json.isEmpty()) return null;
        return WafPolicySnapshot.builder()
                .mode(str(json, "mode") != null ? str(json, "mode") : "detect")
                .maxBodyBytes(json.get("maxBodyBytes") instanceof Number n ? n.longValue() : null)
                .ipAllow(stringList(json, "ipAllow", "ip_allow"))
                .ipDeny(stringList(json, "ipDeny", "ip_deny"))
                .geoAllow(stringList(json, "geoAllow", "geo_allow"))
                .geoDeny(stringList(json, "geoDeny", "geo_deny"))
                .botMode(str(json, "botMode") != null ? str(json, "botMode") : str(json, "bot_mode"))
                .botRpsThreshold(json.get("botRpsThreshold") instanceof Number n ? n.intValue()
                        : (json.get("bot_rps_threshold") instanceof Number n2 ? n2.intValue() : null))
                .botErrorBurst(json.get("botErrorBurst") instanceof Number n ? n.intValue()
                        : (json.get("bot_error_burst") instanceof Number n2 ? n2.intValue() : null))
                .build();
    }

    @SuppressWarnings("unchecked")
    private VersioningSnapshot extractVersioning(ServiceRegistration reg) {
        Map<String, Object> retry = reg.getRetryPolicyJson();
        if (retry == null) return null;
        Map<String, Object> v = null;
        if (retry.get("versioning") instanceof Map<?, ?> nested) {
            v = (Map<String, Object>) nested;
        } else if (retry.containsKey("apiVersion") || retry.containsKey("deprecated")) {
            v = retry;
        }
        if (v == null || v.isEmpty()) return null;
        boolean deprecated = Boolean.TRUE.equals(v.get("deprecated"))
                || "true".equalsIgnoreCase(String.valueOf(v.get("deprecated")));
        return VersioningSnapshot.builder()
                .apiVersion(str(v, "apiVersion") != null ? str(v, "apiVersion") : "v1")
                .deprecated(deprecated)
                .sunsetAt(str(v, "sunsetAt"))
                .successorEndpoint(str(v, "successorEndpoint"))
                .acceptVersionHeader(str(v, "acceptVersionHeader") != null
                        ? str(v, "acceptVersionHeader") : "Accept-Version")
                .build();
    }

    @SuppressWarnings("unchecked")
    private AiPolicySnapshot toAiPolicy(Map<String, Object> json) {
        if (json == null || json.isEmpty()) return null;
        List<Map<String, Object>> providers = List.of();
        if (json.get("providers") instanceof List<?> list) {
            providers = (List<Map<String, Object>>) (List<?>) list;
        }
        List<String> topics = List.of();
        if (json.get("topicAllowlist") instanceof List<?> list) {
            topics = list.stream().map(String::valueOf).toList();
        }
        return AiPolicySnapshot.builder()
                .tokensPerMinute(json.get("tokensPerMinute") instanceof Number n ? n.intValue() : null)
                .requestsPerMinute(json.get("requestsPerMinute") instanceof Number n ? n.intValue() : null)
                .semanticCacheEnabled(Boolean.TRUE.equals(json.get("semanticCacheEnabled")))
                .semanticCacheTtlSeconds(json.get("semanticCacheTtlSeconds") instanceof Number n ? n.intValue() : 300)
                .blockJailbreak(!Boolean.FALSE.equals(json.get("blockJailbreak")))
                .redactPii(Boolean.TRUE.equals(json.get("redactPii")))
                .blockOffTopic(Boolean.TRUE.equals(json.get("blockOffTopic")))
                .topicAllowlist(topics)
                .providers(providers)
                .build();
    }

    private List<TargetInstanceSnapshot> toInstanceSnapshots(ServiceRegistration reg) {
        List<ServiceInstance> instances =
                serviceInstanceRepository.findByServiceIdAndIsActiveTrueOrderByWeightDesc(reg.getId());
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        String defaultHealth = reg.getHealthEndpoint() != null
                ? reg.getHealthEndpoint() : "/actuator/health";
        List<TargetInstanceSnapshot> out = new ArrayList<>();
        for (ServiceInstance i : instances) {
            out.add(TargetInstanceSnapshot.builder()
                    .baseUrl(i.getBaseUrl())
                    .weight(i.getWeight() != null ? i.getWeight() : 100)
                    .zone(i.getZone())
                    .healthStatus(i.getHealthStatus())
                    .healthPath(defaultHealth)
                    .build());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private TrafficPolicySnapshot toTrafficPolicy(ServiceRegistration reg) {
        CircuitBreakerSnapshot cb = null;
        Map<String, Object> cbJson = reg.getCircuitBreakerJson();
        if (cbJson != null && !cbJson.isEmpty()) {
            cb = CircuitBreakerSnapshot.builder()
                    .failureThreshold(intVal(cbJson, "failureThreshold", 5))
                    .successThreshold(intVal(cbJson, "successThreshold", 2))
                    .openSeconds(intVal(cbJson, "openSeconds", 30))
                    .windowSeconds(intVal(cbJson, "windowSeconds", 60))
                    .build();
        } else {
            cb = CircuitBreakerSnapshot.builder()
                    .failureThreshold(5)
                    .successThreshold(2)
                    .openSeconds(30)
                    .windowSeconds(60)
                    .build();
        }

        List<String> retryOn = List.of("502", "503", "504");
        Map<String, Object> retryJson = reg.getRetryPolicyJson();
        if (retryJson != null && retryJson.get("retryOn") instanceof List<?> list) {
            retryOn = list.stream().map(String::valueOf).toList();
        }

        Integer canaryPercent = null;
        List<TargetInstanceSnapshot> canaryInstances = null;
        Integer mirrorPercent = null;
        List<TargetInstanceSnapshot> mirrorInstances = null;
        Map<String, Object> trafficOverlay = null;
        if (retryJson != null && retryJson.get("traffic") instanceof Map<?, ?> t) {
            trafficOverlay = (Map<String, Object>) t;
        }
        if (trafficOverlay != null) {
            if (trafficOverlay.get("canaryPercent") instanceof Number n) {
                canaryPercent = n.intValue();
            }
            if (trafficOverlay.get("mirrorPercent") instanceof Number n) {
                mirrorPercent = n.intValue();
            }
            canaryInstances = instanceListFromMaps(trafficOverlay.get("canaryInstances"),
                    reg.getHealthEndpoint());
            mirrorInstances = instanceListFromMaps(trafficOverlay.get("mirrorInstances"),
                    reg.getHealthEndpoint());
        }

        return TrafficPolicySnapshot.builder()
                .timeoutMs(reg.getTimeoutMs() != null ? reg.getTimeoutMs() : 10000)
                .connectTimeoutMs(retryJson != null && retryJson.get("connectTimeoutMs") instanceof Number n
                        ? n.intValue() : 10000)
                .retryCount(reg.getRetryCount() != null ? reg.getRetryCount() : 2)
                .loadBalanceAlgorithm(reg.getLoadBalanceAlgorithm() != null
                        ? reg.getLoadBalanceAlgorithm() : "ROUND_ROBIN")
                .retryOn(retryOn)
                .circuitBreaker(cb)
                .canaryPercent(canaryPercent)
                .canaryInstances(canaryInstances)
                .mirrorPercent(mirrorPercent)
                .mirrorInstances(mirrorInstances)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<TargetInstanceSnapshot> instanceListFromMaps(Object raw, String healthPath) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        String hp = healthPath != null ? healthPath : "/actuator/health";
        List<TargetInstanceSnapshot> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> map = (Map<String, Object>) m;
            String base = str(map, "baseUrl");
            if (base == null || base.isBlank()) continue;
            out.add(TargetInstanceSnapshot.builder()
                    .baseUrl(base)
                    .weight(map.get("weight") instanceof Number n ? n.intValue() : 100)
                    .zone(str(map, "zone"))
                    .healthStatus(str(map, "healthStatus") != null ? str(map, "healthStatus") : "UP")
                    .healthPath(str(map, "healthPath") != null ? str(map, "healthPath") : hp)
                    .build());
        }
        return out.isEmpty() ? null : out;
    }

    private CachePolicySnapshot toCachePolicy(Map<String, Object> json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        boolean enabled = Boolean.TRUE.equals(json.get("enabled"))
                || "true".equalsIgnoreCase(String.valueOf(json.get("enabled")));
        if (!enabled) {
            return null;
        }
        List<String> methods = List.of("GET", "HEAD");
        if (json.get("methods") instanceof List<?> list) {
            methods = list.stream().map(String::valueOf).toList();
        }
        List<String> vary = List.of();
        if (json.get("varyHeaders") instanceof List<?> list) {
            vary = list.stream().map(String::valueOf).toList();
        }
        return CachePolicySnapshot.builder()
                .enabled(true)
                .ttlSeconds(intVal(json, "ttlSeconds", 60))
                .methods(methods)
                .varyHeaders(vary)
                .cachePrivate(Boolean.TRUE.equals(json.get("cachePrivate")))
                .build();
    }

    private AuthPolicySnapshot toAuthPolicy(Map<String, Object> json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        String type = json.get("type") != null ? String.valueOf(json.get("type")) : "NONE";
        if ("NONE".equalsIgnoreCase(type)) {
            return null;
        }
        List<String> scopes = List.of();
        if (json.get("requiredScopes") instanceof List<?> list) {
            scopes = list.stream().map(String::valueOf).toList();
        }
        List<String> algs = List.of();
        if (json.get("algorithms") instanceof List<?> list) {
            algs = list.stream().map(String::valueOf).toList();
        }
        Boolean requireSig = null;
        if (json.containsKey("requireSignatureVerify")) {
            requireSig = Boolean.TRUE.equals(json.get("requireSignatureVerify"));
        } else if (str(json, "jwksUri") != null || str(json, "discoveryUrl") != null) {
            requireSig = true;
        }
        return AuthPolicySnapshot.builder()
                .type(type)
                .issuer(str(json, "issuer"))
                .audience(str(json, "audience"))
                .jwksUri(str(json, "jwksUri"))
                .discoveryUrl(str(json, "discoveryUrl"))
                .requiredScopes(scopes)
                .headerName(str(json, "headerName") != null ? str(json, "headerName") : "Authorization")
                .requireHttps(Boolean.TRUE.equals(json.get("requireHttps")))
                .requireSignatureVerify(requireSig)
                .clockSkewSeconds(json.get("clockSkewSeconds") instanceof Number n ? n.intValue() : 60)
                .algorithms(algs.isEmpty() ? null : algs)
                .requireClientCertVerify(json.get("requireClientCertVerify") == null
                        || Boolean.TRUE.equals(json.get("requireClientCertVerify")))
                .introspectionUrl(firstNonNull(str(json, "introspectionUrl"), str(json, "introspection_url")))
                .introspectionClientId(firstNonNull(str(json, "introspectionClientId"),
                        str(json, "introspection_client_id")))
                .introspectionClientSecretRef(firstNonNull(str(json, "introspectionClientSecretRef"),
                        str(json, "introspection_client_secret_ref")))
                .build();
    }

    private RateLimitPolicySnapshot resolveRateLimit(String targetService, String endpoint) {
        List<RateLimitPolicy> routePolicies =
                rateLimitPolicyRepository.findByServiceNameAndRouteEndpointAndEnabledTrue(
                        targetService, endpoint);
        RateLimitPolicy chosen = null;
        if (routePolicies != null && !routePolicies.isEmpty()) {
            chosen = routePolicies.get(0);
        } else {
            List<RateLimitPolicy> svc =
                    rateLimitPolicyRepository.findByServiceNameAndEnabledTrue(targetService);
            if (svc != null) {
                for (RateLimitPolicy p : svc) {
                    if ("SERVICE".equalsIgnoreCase(p.getScope())
                            || p.getRouteEndpoint() == null || p.getRouteEndpoint().isBlank()) {
                        chosen = p;
                        break;
                    }
                }
            }
        }
        if (chosen == null) {
            return null;
        }
        return RateLimitPolicySnapshot.builder()
                .scope(chosen.getScope())
                .algorithm(chosen.getAlgorithm())
                .requestsPerSecond(chosen.getRequestsPerSecond())
                .requestsPerMinute(chosen.getRequestsPerMinute())
                .burst(chosen.getBurst())
                .consumerKey(chosen.getConsumerKey())
                .planTier(chosen.getPlanTier())
                .keyBy(chosen.getMetadata() != null && chosen.getMetadata().get("keyBy") != null
                        ? String.valueOf(chosen.getMetadata().get("keyBy")) : "ip")
                .build();
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonNull(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static List<String> stringList(Map<String, Object> json, String camel, String snake) {
        Object raw = json.get(camel);
        if (raw == null) {
            raw = json.get(snake);
        }
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        return list.stream().map(String::valueOf).toList();
    }
}
