package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.openapi.OpenApiImportResult;
import com.selfhealing.gateway.model.OpenApiSpecRegistry;
import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.model.ServiceRoute;
import com.selfhealing.gateway.repository.OpenApiSpecRegistryRepository;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.repository.ServiceRouteRepository;
import com.selfhealing.gateway.util.AllowedSurfaceCompiler;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Second ingestion path beside {@link ManifestImportService}: imports an OpenAPI 3.x
 * document as the source of truth for routing/config, and as a {@code spec_trust}-weighted
 * prior for contract semantics ({@code schema_source=OPENAPI_DECLARED}).
 *
 * <p>Reconciliation is soft: routes/contracts absent from a new import are deactivated
 * with a grace marker, never hard-dropped on a single incomplete auto-generated spec.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiImportService {

    private static final String SCHEMA_SOURCE = "OPENAPI_DECLARED";
    private static final double DEFAULT_SPEC_TRUST = 0.8;

    private final ServiceRegistryService registryService;
    private final ServiceRouteRepository routeRepository;
    private final ServiceContractRepository contractRepository;
    private final OpenApiSpecRegistryRepository specRegistryRepository;
    private final RouteChangedPublisher routeChangedPublisher;
    private final OpenApiFetchGuard fetchGuard;
    private final TopologyGraphWriter topologyGraphWriter;

    public OpenApiImportResult dryRun(String raw) {
        return importInternal(raw, null, null, true);
    }

    @Transactional
    public OpenApiImportResult importSpec(String raw) {
        return importInternal(raw, null, null, false);
    }

    @Transactional
    public OpenApiImportResult importFromUrl(String url) {
        fetchGuard.assertAllowed(url);
        String raw;
        try {
            raw = fetchSpecBody(url);
        } catch (IllegalArgumentException e) {
            return OpenApiImportResult.builder()
                    .success(false)
                    .errors(List.of(e.getMessage()))
                    .build();
        } catch (Exception e) {
            return OpenApiImportResult.builder()
                    .success(false)
                    .errors(List.of("Failed to fetch OpenAPI from URL: " + e.getMessage()))
                    .build();
        }
        return importInternal(raw, url, null, false);
    }

    /**
     * Fetch with connect/read timeouts and a hard body-size cap ({@link OpenApiFetchGuard#MAX_SPEC_BYTES}).
     */
    private String fetchSpecBody(String url) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(OpenApiFetchGuard.CONNECT_TIMEOUT_MS))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofMillis(OpenApiFetchGuard.READ_TIMEOUT_MS))
                .GET()
                .header("Accept", "application/json, application/yaml, text/yaml, */*")
                .build();
        java.net.http.HttpResponse<byte[]> response = client.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("spec URL returned HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("spec URL returned empty body");
        }
        if (body.length > OpenApiFetchGuard.MAX_SPEC_BYTES) {
            throw new IllegalArgumentException("spec exceeds max size of "
                    + OpenApiFetchGuard.MAX_SPEC_BYTES + " bytes");
        }
        // Re-check final URI after redirects against the allowlist
        response.uri();
        if (response.uri() != null && !response.uri().toString().equals(url)) {
            fetchGuard.assertAllowed(response.uri().toString());
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private OpenApiImportResult importInternal(String raw, String url, String etag, boolean dryRun) {
        if (raw == null || raw.isBlank()) {
            return OpenApiImportResult.builder()
                    .success(false)
                    .errors(List.of("OpenAPI document is empty"))
                    .dryRun(dryRun)
                    .build();
        }
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        opts.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(raw, null, opts);
        if (result.getOpenAPI() == null) {
            return OpenApiImportResult.builder()
                    .success(false)
                    .errors(List.of("Failed to parse OpenAPI: "
                            + String.join("; ", nullSafe(result.getMessages()))))
                    .dryRun(dryRun)
                    .build();
        }
        return importParsed(result.getOpenAPI(), url, raw, dryRun);
    }

    private OpenApiImportResult importParsed(OpenAPI openAPI, String url, String raw, boolean dryRun) {
        List<String> warnings = new ArrayList<>();
        List<String> planned = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (resultHasMessages(openAPI, warnings) && openAPI.getPaths() == null) {
            errors.add("OpenAPI document has no paths");
            return fail(errors, warnings, dryRun);
        }

        String serviceName = resolveServiceName(openAPI);
        String baseUrl = resolveBaseUrl(openAPI);
        String ingressHost = resolveExtensionString(openAPI, "x-mendr-host");
        String enforceMode = resolveEnforceMode(openAPI);
        String sourceApp = resolveExtensionString(openAPI, "x-mendr-source");
        if (sourceApp == null || sourceApp.isBlank()) {
            sourceApp = serviceName;
        }

        String hash = sha256(raw != null ? raw : (url == null ? serviceName : url));

        Optional<OpenApiSpecRegistry> existing = specRegistryRepository
                .findBySourceAppAndSpecHashAndIsActiveTrue(sourceApp, hash);
        if (existing.isPresent() && !dryRun) {
            planned.add("spec_hash unchanged — idempotent no-op");
            return OpenApiImportResult.builder()
                    .success(true)
                    .serviceName(serviceName)
                    .specHash(hash)
                    .dryRun(false)
                    .plannedChanges(planned)
                    .warnings(warnings)
                    .build();
        }

        ServiceRegistration.AuthType authType = mapAuth(openAPI);
        List<String> corsOrigins = resolveCors(openAPI);

        planned.add("register service '" + serviceName + "' baseUrl=" + baseUrl
                + " auth=" + authType + " enforce=" + enforceMode);

        int routesCreated = 0, routesUpdated = 0, routesSoft = 0;
        int contractsCreated = 0, contractsUpdated = 0;
        Set<String> seenRouteKeys = new HashSet<>();
        Set<String> seenContractKeys = new HashSet<>();
        Set<String> seenTopologyEdgeKeys = new HashSet<>();

        if (!dryRun) {
            ServiceRegistration reg = ServiceRegistration.builder()
                    .name(serviceName)
                    .baseUrl(baseUrl)
                    .authType(authType)
                    .allowedCallerOrigins(corsOrigins)
                    .description(openAPI.getInfo() != null ? openAPI.getInfo().getDescription() : null)
                    .build();
            // Map x-mendr-auth header name if present
            String authHeader = resolveExtensionString(openAPI, "x-mendr-auth-header");
            if (authHeader != null) {
                reg.setAuthHeaderName(authHeader);
            }
            registryService.register(reg);
        }

        if (openAPI.getPaths() != null) {
            for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
                String path = normalizeEndpoint(pathEntry.getKey());
                PathItem item = pathEntry.getValue();
                Map<PathItem.HttpMethod, Operation> ops = item.readOperationsMap();
                if (ops == null) continue;

                for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : ops.entrySet()) {
                    String method = opEntry.getKey().name();
                    Operation operation = opEntry.getValue();
                    String matchType = path.contains("{") ? "TEMPLATE" : "EXACT";

                    // Provider-side route: external callers (sourceApp) → this service
                    String routeKey = sourceApp + "|" + serviceName + "|" + path + "|" + method;
                    seenRouteKeys.add(routeKey);
                    planned.add("route " + sourceApp + " -> " + serviceName + " " + method + " " + path
                            + " [" + matchType + "]");

                    if (!dryRun) {
                        var existingRoute = routeRepository
                                .findBySourceServiceAndTargetServiceAndEndpointAndHttpMethod(
                                        sourceApp, serviceName, path, method);
                        if (existingRoute.isPresent()) {
                            ServiceRoute r = existingRoute.get();
                            r.setActive(true);
                            r.setMatchType(matchType);
                            r.setDescription(operation.getSummary());
                            routeRepository.save(r);
                            routesUpdated++;
                        } else {
                            routeRepository.save(ServiceRoute.builder()
                                    .sourceService(sourceApp)
                                    .targetService(serviceName)
                                    .endpoint(path)
                                    .httpMethod(method)
                                    .matchType(matchType)
                                    .description(operation.getSummary())
                                    .isActive(true)
                                    .build());
                            routesCreated++;
                        }
                        // Topology graph side-effect: declared edge sourceApp -> serviceName.
                        // service_routes stays the operational routing config; this is the map.
                        topologyGraphWriter.recordDeclaredEdge(sourceApp, serviceName, path, method,
                                TopologyGraphWriter.SOURCE_OPENAPI_DECLARED, DEFAULT_SPEC_TRUST);
                        String topoKey = TopologyGraphWriter.edgeKey(sourceApp, serviceName, path);
                        if (topoKey != null) {
                            seenTopologyEdgeKeys.add(topoKey);
                        }
                    } else {
                        routesCreated++;
                    }

                    // REQUEST contract
                    Map<String, Object> reqSchema = extractRequestSchema(operation);
                    List<String> queryParams = extractQueryParams(operation);
                    Map<String, Object> example = extractRequestExample(operation);
                    if (reqSchema != null || example != null) {
                        String ck = serviceName + "|" + path + "|" + method + "|REQUEST";
                        seenContractKeys.add(ck);
                        if (!dryRun) {
                            boolean created = upsertContract(serviceName, path, method, "REQUEST",
                                    example, reqSchema, queryParams, enforceMode,
                                    operation.getSummary());
                            if (created) contractsCreated++; else contractsUpdated++;
                        } else {
                            contractsCreated++;
                            planned.add("contract REQUEST " + method + " " + path);
                        }
                    }

                    // RESPONSE contract (prefer 200 / 201 / default 2xx)
                    Map<String, Object> respSchema = extractResponseSchema(operation);
                    Map<String, Object> respExample = extractResponseExample(operation);
                    if (respSchema != null || respExample != null) {
                        String ck = serviceName + "|" + path + "|" + method + "|RESPONSE";
                        seenContractKeys.add(ck);
                        if (!dryRun) {
                            boolean created = upsertContract(serviceName, path, method, "RESPONSE",
                                    respExample, respSchema, List.of(), enforceMode,
                                    operation.getSummary());
                            if (created) contractsCreated++; else contractsUpdated++;
                        } else {
                            contractsCreated++;
                            planned.add("contract RESPONSE " + method + " " + path);
                        }
                    }
                }
            }
        }

        // Soft-prune routes for this source→target that are no longer in the spec
        if (!dryRun) {
            for (ServiceRoute r : routeRepository.findByIsActiveTrue()) {
                if (!serviceName.equals(r.getTargetService())) continue;
                if (!sourceApp.equals(r.getSourceService())) continue;
                String key = r.getSourceService() + "|" + r.getTargetService() + "|"
                        + r.getEndpoint() + "|" + r.getHttpMethod();
                if (!seenRouteKeys.contains(key)) {
                    r.setActive(false);
                    routeRepository.save(r);
                    routesSoft++;
                    planned.add("soft-deactivate route " + key);
                }
            }

            OpenApiSpecRegistry row = OpenApiSpecRegistry.builder()
                    .sourceApp(sourceApp)
                    .specUrl(url)
                    .specHash(hash)
                    .version(openAPI.getInfo() != null ? openAPI.getInfo().getVersion() : null)
                    .ingressHost(ingressHost)
                    .enforceMode(enforceMode)
                    .rawSpec(raw)
                    .isActive(true)
                    .build();
            specRegistryRepository.save(row);

            // Topology drift: close OPENAPI_DECLARED edges into this service that the fresh
            // spec no longer declares (SCD2 valid_to, never a hard delete), then rebuild the
            // content-addressed adjacency snapshot / graph_version once for the whole import.
            try {
                topologyGraphWriter.closeAbsentDeclaredEdges(
                        sourceApp, serviceName, TopologyGraphWriter.SOURCE_OPENAPI_DECLARED, seenTopologyEdgeKeys);
                topologyGraphWriter.rebuildSnapshot();
            } catch (Exception e) {
                log.warn("Topology snapshot rebuild after OpenAPI import of '{}' skipped: {}",
                        serviceName, e.getMessage());
            }

            routeChangedPublisher.publishAll();
        }

        return OpenApiImportResult.builder()
                .success(true)
                .serviceName(serviceName)
                .specHash(hash)
                .dryRun(dryRun)
                .routesCreated(routesCreated)
                .routesUpdated(routesUpdated)
                .routesSoftDeactivated(routesSoft)
                .contractsCreated(contractsCreated)
                .contractsUpdated(contractsUpdated)
                .warnings(warnings)
                .plannedChanges(planned)
                .build();
    }

    private boolean upsertContract(String service, String endpoint, String method, String direction,
                                   Map<String, Object> example, Map<String, Object> schema,
                                   List<String> queryParams, String enforceMode, String description) {
        Map<String, Object> surface = AllowedSurfaceCompiler.compile(
                schema, queryParams, SCHEMA_SOURCE, DEFAULT_SPEC_TRUST);

        var existing = contractRepository
                .findByServiceNameAndEndpointAndHttpMethodAndDirectionAndVersion(
                        service, endpoint, method, direction, "1.0");

        if (existing.isPresent()) {
            ServiceContract c = existing.get();
            c.setActive(true);
            if (example != null) c.setExamplePayload(example);
            if (schema != null) c.setInferredSchema(schema);
            c.setSchemaSource(SCHEMA_SOURCE);
            c.setSpecTrust(DEFAULT_SPEC_TRUST);
            c.setAllowedSurface(surface);
            c.setEnforceMode(enforceMode);
            c.setRegisteredBy("openapi-import");
            if (description != null) c.setDescription(description);
            contractRepository.save(c);
            return false;
        }

        contractRepository.save(ServiceContract.builder()
                .serviceName(service)
                .endpoint(endpoint)
                .httpMethod(method)
                .direction(direction)
                .examplePayload(example != null ? example : Map.of())
                .inferredSchema(schema)
                .schemaSource(SCHEMA_SOURCE)
                .specTrust(DEFAULT_SPEC_TRUST)
                .allowedSurface(surface)
                .enforceMode(enforceMode)
                .version("1.0")
                .description(description)
                .registeredBy("openapi-import")
                .isActive(true)
                .build());
        return true;
    }

    // ── OpenAPI field extractors ────────────────────────────────────────────

    private String resolveServiceName(OpenAPI openAPI) {
        String ext = resolveExtensionString(openAPI, "x-mendr-service");
        if (ext != null && !ext.isBlank()) return ext.trim();
        if (openAPI.getInfo() != null && openAPI.getInfo().getTitle() != null) {
            return slugify(openAPI.getInfo().getTitle());
        }
        return "imported-service";
    }

    private String resolveBaseUrl(OpenAPI openAPI) {
        List<Server> servers = openAPI.getServers();
        if (servers != null && !servers.isEmpty() && servers.get(0).getUrl() != null) {
            return servers.get(0).getUrl();
        }
        return "http://localhost:8080";
    }

    private String resolveEnforceMode(OpenAPI openAPI) {
        Object ext = extensions(openAPI).get("x-mendr-enforce");
        if (ext != null && "strict".equalsIgnoreCase(String.valueOf(ext))) {
            return "strict";
        }
        return "observe";
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveCors(OpenAPI openAPI) {
        Object ext = extensions(openAPI).get("x-mendr-cors");
        if (ext instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) out.add(String.valueOf(o));
            return out;
        }
        if (ext instanceof Map<?, ?> map && map.get("origins") instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }

    private ServiceRegistration.AuthType mapAuth(OpenAPI openAPI) {
        Object override = extensions(openAPI).get("x-mendr-auth");
        if (override != null) {
            try {
                return ServiceRegistration.AuthType.valueOf(String.valueOf(override).toUpperCase(Locale.ROOT));
            } catch (Exception ignored) { /* fall through */ }
        }
        if (openAPI.getComponents() == null || openAPI.getComponents().getSecuritySchemes() == null) {
            return ServiceRegistration.AuthType.NONE;
        }
        for (SecurityScheme scheme : openAPI.getComponents().getSecuritySchemes().values()) {
            if (scheme.getType() == SecurityScheme.Type.HTTP
                    && "bearer".equalsIgnoreCase(scheme.getScheme())) {
                return ServiceRegistration.AuthType.JWT_BEARER;
            }
            if (scheme.getType() == SecurityScheme.Type.APIKEY
                    && scheme.getIn() == SecurityScheme.In.HEADER) {
                return ServiceRegistration.AuthType.API_KEY_HEADER;
            }
            if (scheme.getType() == SecurityScheme.Type.HTTP
                    && "basic".equalsIgnoreCase(scheme.getScheme())) {
                return ServiceRegistration.AuthType.BASIC;
            }
        }
        return ServiceRegistration.AuthType.NONE;
    }

    @SuppressWarnings("rawtypes")
    private Map<String, Object> extractRequestSchema(Operation op) {
        if (op.getRequestBody() == null || op.getRequestBody().getContent() == null) return null;
        Schema schema = firstJsonSchema(op.getRequestBody().getContent());
        return schemaToMap(schema);
    }

    @SuppressWarnings("rawtypes")
    private Map<String, Object> extractResponseSchema(Operation op) {
        if (op.getResponses() == null) return null;
        for (String code : List.of("200", "201", "202", "204")) {
            ApiResponse resp = op.getResponses().get(code);
            if (resp != null && resp.getContent() != null) {
                Schema schema = firstJsonSchema(resp.getContent());
                Map<String, Object> mapped = schemaToMap(schema);
                if (mapped != null) return mapped;
            }
        }
        ApiResponse def = op.getResponses().getDefault();
        if (def != null && def.getContent() != null) {
            return schemaToMap(firstJsonSchema(def.getContent()));
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private Map<String, Object> extractRequestExample(Operation op) {
        if (op.getRequestBody() == null || op.getRequestBody().getContent() == null) return null;
        return firstExample(op.getRequestBody().getContent());
    }

    @SuppressWarnings("rawtypes")
    private Map<String, Object> extractResponseExample(Operation op) {
        if (op.getResponses() == null) return null;
        for (String code : List.of("200", "201")) {
            ApiResponse resp = op.getResponses().get(code);
            if (resp != null && resp.getContent() != null) {
                Map<String, Object> ex = firstExample(resp.getContent());
                if (ex != null) return ex;
            }
        }
        return null;
    }

    private List<String> extractQueryParams(Operation op) {
        List<String> out = new ArrayList<>();
        if (op.getParameters() == null) return out;
        for (Parameter p : op.getParameters()) {
            if (p != null && "query".equalsIgnoreCase(p.getIn()) && p.getName() != null) {
                out.add(p.getName());
            }
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    private Schema firstJsonSchema(Content content) {
        if (content == null) return null;
        MediaType mt = content.get("application/json");
        if (mt == null) {
            for (MediaType candidate : content.values()) {
                if (candidate.getSchema() != null) return candidate.getSchema();
            }
            return null;
        }
        return mt.getSchema();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> firstExample(Content content) {
        if (content == null) return null;
        MediaType mt = content.get("application/json");
        if (mt == null && !content.isEmpty()) {
            mt = content.values().iterator().next();
        }
        if (mt == null) return null;
        Object example = mt.getExample();
        if (example instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        if (mt.getExamples() != null && !mt.getExamples().isEmpty()) {
            Object v = mt.getExamples().values().iterator().next().getValue();
            if (v instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
        }
        // Generate a shallow example from schema properties when none provided
        return generateExample(mt.getSchema());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> schemaToMap(Schema schema) {
        if (schema == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema.getType() != null) out.put("type", schema.getType());
        if (schema.getFormat() != null) out.put("format", schema.getFormat());
        if (schema.getRequired() != null) out.put("required", schema.getRequired());
        if (schema.getEnum() != null) out.put("enum", schema.getEnum());
        if (schema.getProperties() != null) {
            Map<String, Object> props = new LinkedHashMap<>();
            schema.getProperties().forEach((k, v) -> props.put(String.valueOf(k), schemaToMap((Schema) v)));
            out.put("properties", props);
        }
        if (schema.getAdditionalProperties() != null) {
            out.put("additionalProperties", schema.getAdditionalProperties());
        }
        return out.isEmpty() ? null : out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> generateExample(Schema schema) {
        if (schema == null || schema.getProperties() == null) return null;
        Map<String, Object> ex = new LinkedHashMap<>();
        schema.getProperties().forEach((k, v) -> {
            Schema child = (Schema) v;
            Object val = switch (child != null && child.getType() != null ? child.getType() : "string") {
                case "integer", "number" -> 0;
                case "boolean" -> false;
                case "array" -> List.of();
                case "object" -> generateExample(child);
                default -> "string";
            };
            ex.put(String.valueOf(k), val);
        });
        return ex.isEmpty() ? null : ex;
    }

    // ── SSRF + helpers ──────────────────────────────────────────────────────

    private Map<String, Object> extensions(OpenAPI openAPI) {
        Map<String, Object> ext = openAPI.getExtensions();
        return ext == null ? Map.of() : ext;
    }

    private String resolveExtensionString(OpenAPI openAPI, String key) {
        Object v = extensions(openAPI).get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return "/";
        String e = endpoint.trim();
        if (!e.startsWith("/")) e = "/" + e;
        return e;
    }

    private static String slugify(String title) {
        return title.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static List<String> nullSafe(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static boolean resultHasMessages(OpenAPI openAPI, List<String> warnings) {
        return openAPI != null;
    }

    private static OpenApiImportResult fail(List<String> errors, List<String> warnings, boolean dryRun) {
        return OpenApiImportResult.builder()
                .success(false)
                .errors(errors)
                .warnings(warnings)
                .dryRun(dryRun)
                .build();
    }
}
