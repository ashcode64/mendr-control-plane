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

    private String authType;
    private String authHeaderName;

    private TransformProgramSnapshot requestProgram;
    private TransformProgramSnapshot responseProgram;

    /** Approved upstream Origin header overrides for this route */
    private List<OriginOverrideSnapshot> originOverrides;

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
        private Map<String, String> renames;
        private Map<String, Object> defaults;
        private Map<String, String> coercions;
        private Set<String> removals;
        private String wrapKey;
        private String unwrapKey;
    }
}
