package com.selfhealing.gateway.dto.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-level Mendr service manifest (mendr.yaml / mendr.json).
 *
 * <p>Bound from uploaded YAML/JSON — never persisted directly. The
 * {@code ManifestImportService} maps this into {@code ServiceRegistration},
 * {@code ServiceContract}, and {@code ServiceRoute} entities.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceManifest {

    private String apiVersion;
    private String kind;

    private ServiceSpec service;

    private List<InboundApi> inbound = new ArrayList<>();
    private List<OutboundCall> outbound = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServiceSpec {
        private String name;
        private String baseUrl;
        private String namespace;
        private String description;
        private String teamEmail;
        private String healthEndpoint;
        private Integer timeoutMs;
        private Integer retryCount;
        private AuthSpec auth;
        private List<String> allowedCallerOrigins = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthSpec {
        /** NONE | JWT_BEARER | API_KEY_HEADER | API_KEY_QUERY | BASIC */
        private String type;
        private String headerName;
        /** Env var NAME only — never the secret value itself. */
        private String secretRef;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InboundApi {
        private String endpoint;
        private String method;
        private String version;
        private String description;
        private PayloadSpec request;
        private PayloadSpec response;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutboundCall {
        private String targetService;
        private String endpoint;
        private String method;
        /** EXACT | PREFIX | TEMPLATE — only EXACT supported today. */
        private String matchType;
        private String version;
        private String description;
        private PayloadSpec request;
        private PayloadSpec response;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PayloadSpec {
        private Map<String, Object> example;
    }
}
