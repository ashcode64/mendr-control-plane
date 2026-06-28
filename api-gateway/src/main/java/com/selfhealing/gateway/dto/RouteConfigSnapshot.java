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
    }
}
