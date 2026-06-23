package com.selfhealing.gateway.model;

import com.selfhealing.gateway.transform.TransformProgram;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Set;

/**
 * Compiled per-route snapshot for the hot path — one lookup replaces
 * separate Redis/DB hops for URL, auth, rules, CORS, and contracts.
 */
@Value
@Builder
public class RouteConfig {

    String sourceService;
    String targetService;
    String endpoint;
    String targetBaseUrl;
    String registeredBaseUrl;

    ServiceRegistration.AuthType authType;
    String authHeaderName;
    String authSecretRef;

    boolean hasRequestRules;
    List<TransformationRule> requestRules;

    boolean hasResponseRules;
    List<ResponseTransformationRule> responseRules;

    boolean corsActive;
    Set<String> allowedOrigins;

    boolean hasResponseContract;

    @Builder.Default
    TransformProgram requestProgram = TransformProgram.none();

    @Builder.Default
    TransformProgram responseProgram = TransformProgram.none();

    @Builder.Default
    List<OriginOverrideSpec> originOverrides = List.of();

    public record OriginOverrideSpec(
            String callerOriginMatch,
            String outboundOriginOverride,
            boolean rewriteResponseAcao) {}

    public boolean fastPathEligible() {
        return !hasRequestRules
                && !hasResponseRules
                && !corsActive
                && !hasResponseContract
                && (originOverrides == null || originOverrides.isEmpty());
    }

    /** Flat/streamable rules with no response contract — uses T1/T2/T3 streaming path. */
    public boolean streamPathEligible() {
        return !fastPathEligible()
                && requestProgram != null && requestProgram.isStreamable()
                && responseProgram != null && responseProgram.isStreamable()
                && !hasResponseContract;
    }

    public boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) return false;
        if (!corsActive) return false;
        return allowedOrigins.contains(origin) || allowedOrigins.contains("*");
    }
}
