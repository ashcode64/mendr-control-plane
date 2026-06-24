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
        /** Single canonical example (back-compat). */
        private Map<String, Object> example;
        /** Optional additional examples — together they sharpen required/optional inference. */
        private List<Map<String, Object>> examples = new ArrayList<>();
        /** Optional author-supplied schema; if absent one is inferred from the example(s). */
        private Map<String, Object> schema;

        /** All examples, with the canonical {@code example} first if present. */
        public List<Map<String, Object>> allExamples() {
            List<Map<String, Object>> all = new ArrayList<>();
            if (example != null && !example.isEmpty()) {
                all.add(example);
            }
            if (examples != null) {
                for (Map<String, Object> e : examples) {
                    if (e != null && !e.isEmpty()) all.add(e);
                }
            }
            return all;
        }

        /** The primary example for storage/back-compat: the canonical one, else the first. */
        public Map<String, Object> primaryExample() {
            if (example != null && !example.isEmpty()) return example;
            List<Map<String, Object>> all = allExamples();
            return all.isEmpty() ? null : all.get(0);
        }
    }
}
