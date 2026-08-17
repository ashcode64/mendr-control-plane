package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain JSON snapshot for OpenResty/Lua — no Java class metadata.
 * Mirrors {@link com.selfhealing.gateway.model.RouteConfig} + compiled programs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteConfigSnapshot {

    private String sourceService;
    private String targetService;
    private String endpoint;

    private String targetBaseUrl;
    private String registeredBaseUrl;

    private boolean corsActive;
    private Set<String> allowedOrigins;
    private boolean hasResponseContract;

    /** Per-route sync validation flag. When true, Lua skips async validation and
     *  the Java proxy path handles response contract checking synchronously. */
    private boolean syncValidation;

    /** sha256 of the merged materialized program; edges may compare for drift detection. */
    private String programHash;

    private String authType;
    private String authHeaderName;

    private TransformProgramSnapshot requestProgram;
    private TransformProgramSnapshot responseProgram;

    /** Approved upstream Origin header overrides for this route */
    private List<OriginOverrideSnapshot> originOverrides;

    /**
     * {@code observe} (default) or {@code strict}. When {@code strict}, the edge
     * rejects undeclared body fields / query params using {@link #allowedSurface}.
     */
    private String enforceMode;

    /**
     * AOT-compiled allowed surface for strict enforcement:
     * bodyPointers, queryParams, additionalProperties, schemaSource, specTrust.
     */
    private Map<String, Object> allowedSurface;

    /**
     * Multi-instance upstream pool (capability {@code traffic}). When present and non-empty,
     * the edge load-balances across these instead of only {@link #targetBaseUrl}.
     */
    private List<TargetInstanceSnapshot> targetInstances;

    /**
     * Timeouts, retries, load-balance algorithm, circuit breaker (capability {@code traffic}).
     */
    private TrafficPolicySnapshot trafficPolicy;

    /**
     * Per-route / consumer / tenant quota (capability {@code ratelimit}).
     */
    private RateLimitPolicySnapshot rateLimitPolicy;

    /**
     * Consumer-facing auth at the edge: JWT/OIDC/API-key (capability {@code authz}).
     * Distinct from upstream {@link #authType} credential pass-through.
     */
    private AuthPolicySnapshot authPolicy;

    /**
     * Response cache policy (capability {@code cache}).
     */
    private CachePolicySnapshot cachePolicy;

    /** WAF / threat policy projected when edge advertises {@code waf}. */
    private WafPolicySnapshot wafPolicy;

    /** AI gateway policy (TPM, firewall, semantic cache) when protocol=AI or path matches. */
    private AiPolicySnapshot aiPolicy;

    /** Upstream protocol hint: HTTP | HTTP2 | GRPC | WEBSOCKET | AI */
    private String protocol;

    /** Active health-check path for target service (default /actuator/health). */
    private String healthEndpoint;

    /**
     * API versioning / deprecation (capability {@code traffic}).
     * Edge emits RFC 8594 Deprecation/Sunset headers and may route by Accept-Version.
     */
    private VersioningSnapshot versioning;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VersioningSnapshot {
        private String apiVersion;
        private boolean deprecated;
        private String sunsetAt;
        private String successorEndpoint;
        private String acceptVersionHeader;
    }

    /**
     * Tenant plan quotas projected for edge enforcement (capability {@code ratelimit}).
     */
    private TenantQuotaSnapshot tenantQuota;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantQuotaSnapshot {
        private String tenantId;
        private Integer quotaRpm;
        private Integer quotaRpd;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TargetInstanceSnapshot {
        private String baseUrl;
        private int weight;
        private String zone;
        private String healthStatus;
        private String healthPath;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrafficPolicySnapshot {
        private Integer timeoutMs;
        private Integer connectTimeoutMs;
        private Integer retryCount;
        /** ROUND_ROBIN | WEIGHTED | CONSISTENT_HASH */
        private String loadBalanceAlgorithm;
        /** error statuses to retry, e.g. ["502","503","504"] */
        private List<String> retryOn;
        private CircuitBreakerSnapshot circuitBreaker;
        /**
         * Canary / traffic split: percent of requests (0–100) routed to {@link #canaryInstances}.
         * Remaining traffic uses the primary {@code targetInstances} pool.
         */
        private Integer canaryPercent;
        private List<TargetInstanceSnapshot> canaryInstances;
        /**
         * Shadow / mirror: percent of requests (0–100) asynchronously mirrored to
         * {@link #mirrorInstances} (response discarded).
         */
        private Integer mirrorPercent;
        private List<TargetInstanceSnapshot> mirrorInstances;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CircuitBreakerSnapshot {
        private int failureThreshold;
        private int successThreshold;
        private int openSeconds;
        private int windowSeconds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitPolicySnapshot {
        private String scope;
        private String algorithm;
        private Double requestsPerSecond;
        private Integer requestsPerMinute;
        private Integer burst;
        private String consumerKey;
        private String planTier;
        private String keyBy; // ip | consumer | route
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthPolicySnapshot {
        /** NONE | JWT | OIDC | API_KEY | MTLS */
        private String type;
        private String issuer;
        private String audience;
        private String jwksUri;
        /** OIDC discovery URL (alternative to jwksUri). */
        private String discoveryUrl;
        private List<String> requiredScopes;
        private String headerName;
        private boolean requireHttps;
        /** When true (default if jwksUri set), reject tokens that fail signature verify. */
        private Boolean requireSignatureVerify;
        private Integer clockSkewSeconds;
        private List<String> algorithms;
        private Boolean requireClientCertVerify;
        private String introspectionUrl;
        private String introspectionClientId;
        private String introspectionClientSecretRef;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WafPolicySnapshot {
        /** off | detect | block */
        private String mode;
        private Long maxBodyBytes;
        private List<String> ipAllow;
        private List<String> ipDeny;
        private List<String> geoAllow;
        private List<String> geoDeny;
        /** off | detect | block — volumetric / bot scoring */
        private String botMode;
        /** Requests/sec from one IP that trip bot score (default 80). */
        private Integer botRpsThreshold;
        /** Distinct 4xx spike threshold in 60s window. */
        private Integer botErrorBurst;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiPolicySnapshot {
        private Integer tokensPerMinute;
        private Integer requestsPerMinute;
        private boolean semanticCacheEnabled;
        private Integer semanticCacheTtlSeconds;
        private boolean blockJailbreak;
        private boolean redactPii;
        private boolean blockOffTopic;
        private List<String> topicAllowlist;
        private List<Map<String, Object>> providers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CachePolicySnapshot {
        private boolean enabled;
        private int ttlSeconds;
        private List<String> methods;
        private List<String> varyHeaders;
        private boolean cachePrivate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OriginOverrideSnapshot {
        private String callerOriginMatch;
        private String outboundOriginOverride;
        private boolean rewriteResponseAcao;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransformProgramSnapshot {
        private boolean empty;
        private boolean streamable;
        /**
         * MendrScript snapshot schema version. {@code "v1"} = legacy six-bucket only.
         * {@code "v2"} = also carries {@link #ops}. Lets capability negotiation pick
         * the right shape per edge (Gap 10).
         */
        private String schemaVersion;
        /**
         * MendrScript AST ops (snapshot v2). Each entry is a plain-JSON op object
         * ({@code {op, ...args}}) the edge interpreter walks as DATA. Legacy buckets
         * below stay populated so v1 edges keep working; upgraded edges prefer ops[].
         */
        private List<Map<String, Object>> ops;
        private Map<String, String> renames;
        private Map<String, Object> defaults;
        private Map<String, String> coercions;
        private Set<String> removals;
        private String wrapKey;
        private String unwrapKey;
        /** FIELD_MOVE restructure ops: each {from, to, copy?} with JSON-Pointer paths. */
        private List<Map<String, Object>> moves;
        /** SCALE value ops: each {path, numerator, denominator, expectedMin, expectedMax}. */
        private List<Map<String, Object>> scales;
        /** COALESCE ops: each {path, value}, applied only when current value is null. */
        private List<Map<String, Object>> coalesce;
        /** MAP_VALUE ops: each {path, mapping, onUnmapped}. */
        private List<Map<String, Object>> valueMaps;
        /** REFORMAT_DATE ops: each {path, sourceFormat, targetFormat}. */
        private List<Map<String, Object>> dateFormats;
        /** STRIP_UNKNOWN ops: each {path, allowed:[...]}. */
        private List<Map<String, Object>> stripUnknown;
        /** WRAP_ARRAY ops: each {path}. */
        private List<Map<String, Object>> wrapArrays;
        /** UNWRAP_ARRAY ops: each {path}. */
        private List<Map<String, Object>> unwrapArrays;
        /** Execution class (PASSTHROUGH|PREFILTERABLE|FORWARD_ONLY|BOUNDED_WINDOW|UNBOUNDED). */
        private String planClass;
        private List<String> prefilterLiterals;
        private List<String> writePointers;
        private String maxWindowDepth;
        private Boolean prefilterable;
    }
}
