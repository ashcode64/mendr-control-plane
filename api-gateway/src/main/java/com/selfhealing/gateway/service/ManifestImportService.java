package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.selfhealing.gateway.dto.manifest.ManifestImportResult;
import com.selfhealing.gateway.dto.manifest.ManifestValidationException;
import com.selfhealing.gateway.dto.manifest.ServiceManifest;
import com.selfhealing.gateway.dto.manifest.ServiceManifest.InboundApi;
import com.selfhealing.gateway.dto.manifest.ServiceManifest.OutboundCall;
import com.selfhealing.gateway.dto.manifest.ServiceManifest.PayloadSpec;
import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.model.ServiceRoute;
import com.selfhealing.gateway.repository.ServiceRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses, validates and imports a Mendr service manifest (YAML or JSON):
 * registers the service, persists request/response examples as contracts
 * (feeding the AI engine), and creates explicit outbound route declarations.
 *
 * <p>Validation runs fully before any persistence, and the whole import is
 * transactional, so a bad manifest never half-registers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManifestImportService {

    private static final Set<String> VALID_AUTH_TYPES = Set.of(
            "NONE", "JWT_BEARER", "API_KEY_HEADER", "API_KEY_QUERY", "BASIC");
    private static final Set<String> VALID_MATCH_TYPES = Set.of("EXACT", "PREFIX", "TEMPLATE");
    private static final Set<String> SUPPORTED_MATCH_TYPES = Set.of("EXACT");

    private final ServiceRegistryService registryService;
    private final ServiceRouteRepository routeRepository;
    private final RouteChangedPublisher routeChangedPublisher;
    private final ObjectMapper jsonMapper;

    private final YAMLMapper yamlMapper = new YAMLMapper();

    /** Parse raw manifest text (YAML or JSON, auto-detected) into a DTO. */
    public ServiceManifest parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ManifestValidationException(List.of("Manifest is empty"));
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return jsonMapper.readValue(trimmed, ServiceManifest.class);
            }
            return yamlMapper.readValue(trimmed, ServiceManifest.class);
        } catch (Exception jsonOrYaml) {
            // Fall back to the other format before giving up.
            try {
                return yamlMapper.readValue(trimmed, ServiceManifest.class);
            } catch (Exception ignored) {
                throw new ManifestValidationException(List.of(
                        "Could not parse manifest as YAML or JSON: " + jsonOrYaml.getMessage()));
            }
        }
    }

    @Transactional
    public ManifestImportResult importManifest(String raw) {
        ServiceManifest manifest = parse(raw);

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        validate(manifest, errors, warnings);
        if (!errors.isEmpty()) {
            throw new ManifestValidationException(errors);
        }

        ServiceManifest.ServiceSpec spec = manifest.getService();
        String serviceName = spec.getName().trim();

        ServiceRegistration registration = toRegistration(spec);
        registryService.register(registration);

        List<String> contractLines = new ArrayList<>();
        List<String> routeLines = new ArrayList<>();

        // Inbound APIs → provider-side contracts on this service.
        for (InboundApi api : nullSafe(manifest.getInbound())) {
            String endpoint = normalizeEndpoint(api.getEndpoint());
            String method = upper(api.getMethod(), "POST");
            String version = blankToDefault(api.getVersion(), "1.0");
            registerContractIfPresent(serviceName, endpoint, method, "REQUEST", version,
                    api.getDescription(), api.getRequest(), contractLines);
            registerContractIfPresent(serviceName, endpoint, method, "RESPONSE", version,
                    api.getDescription(), api.getResponse(), contractLines);
        }

        // Outbound calls → explicit routes + caller-side contracts.
        Set<String> seenRouteKeys = new HashSet<>();
        for (OutboundCall call : nullSafe(manifest.getOutbound())) {
            String target = call.getTargetService().trim();
            String endpoint = normalizeEndpoint(call.getEndpoint());
            String method = upper(call.getMethod(), "POST");
            String matchType = upper(call.getMatchType(), "EXACT");
            String version = blankToDefault(call.getVersion(), "1.0");

            String dedupeKey = target + "|" + endpoint + "|" + method;
            if (!seenRouteKeys.add(dedupeKey)) {
                warnings.add("Duplicate outbound route skipped: " + method + " " + target + endpoint);
                continue;
            }

            upsertRoute(serviceName, target, endpoint, method, matchType, call.getDescription(), routeLines);

            // Caller-side expectations of the downstream contract (same endpoint).
            registerContractIfPresent(serviceName, endpoint, method, "REQUEST", version,
                    call.getDescription(), call.getRequest(), contractLines);
            registerContractIfPresent(serviceName, endpoint, method, "RESPONSE", version,
                    call.getDescription(), call.getResponse(), contractLines);
        }

        // Single republish after all entities are persisted.
        routeChangedPublisher.publishAll();

        log.info("Imported manifest for '{}': {} contracts, {} routes, {} warnings",
                serviceName, contractLines.size(), routeLines.size(), warnings.size());

        return ManifestImportResult.builder()
                .success(true)
                .service(serviceName)
                .contractsCreated(contractLines.size())
                .routesCreated(routeLines.size())
                .contracts(contractLines)
                .routes(routeLines)
                .warnings(warnings)
                .build();
    }

    // ── Validation ─────────────────────────────────────────────────────────

    private void validate(ServiceManifest manifest, List<String> errors, List<String> warnings) {
        ServiceManifest.ServiceSpec spec = manifest.getService();
        if (spec == null) {
            errors.add("Missing required 'service' section");
            return;
        }
        if (isBlank(spec.getName())) {
            errors.add("service.name is required");
        }
        if (isBlank(spec.getBaseUrl())) {
            errors.add("service.baseUrl is required");
        }

        if (spec.getAuth() != null && !isBlank(spec.getAuth().getType())) {
            String type = spec.getAuth().getType().trim().toUpperCase(Locale.ROOT);
            if (!VALID_AUTH_TYPES.contains(type)) {
                errors.add("Unknown auth.type '" + spec.getAuth().getType()
                        + "'. Valid: " + VALID_AUTH_TYPES);
            }
            String secretRef = spec.getAuth().getSecretRef();
            if (looksLikeInlineSecret(secretRef)) {
                warnings.add("auth.secretRef '" + secretRef + "' looks like an inline secret. "
                        + "Use the NAME of an environment variable, not the value.");
            }
        }

        String serviceName = spec.getName() != null ? spec.getName().trim() : null;
        Set<String> manifestRouteKeys = new HashSet<>();
        int idx = 0;
        for (OutboundCall call : nullSafe(manifest.getOutbound())) {
            String prefix = "outbound[" + idx + "]";
            if (isBlank(call.getTargetService())) {
                errors.add(prefix + ".targetService is required");
            }
            if (isBlank(call.getEndpoint())) {
                errors.add(prefix + ".endpoint is required");
            }
            if (!isBlank(call.getMatchType())) {
                String mt = call.getMatchType().trim().toUpperCase(Locale.ROOT);
                if (!VALID_MATCH_TYPES.contains(mt)) {
                    errors.add(prefix + ".matchType '" + call.getMatchType() + "' is invalid. Valid: " + VALID_MATCH_TYPES);
                } else if (!SUPPORTED_MATCH_TYPES.contains(mt)) {
                    errors.add(prefix + ".matchType '" + mt + "' is not supported yet. Only EXACT is supported.");
                }
            }
            if (!isBlank(call.getTargetService()) && serviceName != null
                    && serviceName.equalsIgnoreCase(call.getTargetService().trim())) {
                errors.add(prefix + " is self-referential (targetService == service.name)");
            }
            if (!isBlank(call.getTargetService()) && !isBlank(call.getEndpoint())) {
                String key = call.getTargetService().trim() + "|"
                        + normalizeEndpoint(call.getEndpoint()) + "|"
                        + upper(call.getMethod(), "POST");
                if (!manifestRouteKeys.add(key)) {
                    warnings.add("Duplicate outbound route in manifest: "
                            + upper(call.getMethod(), "POST") + " "
                            + call.getTargetService().trim() + normalizeEndpoint(call.getEndpoint()));
                }
            }
            validateExample(call.getRequest(), prefix + ".request", errors);
            validateExample(call.getResponse(), prefix + ".response", errors);
            idx++;
        }

        int inIdx = 0;
        for (InboundApi api : nullSafe(manifest.getInbound())) {
            String prefix = "inbound[" + inIdx + "]";
            if (isBlank(api.getEndpoint())) {
                errors.add(prefix + ".endpoint is required");
            }
            validateExample(api.getRequest(), prefix + ".request", errors);
            validateExample(api.getResponse(), prefix + ".response", errors);
            inIdx++;
        }
    }

    private void validateExample(PayloadSpec payload, String prefix, List<String> errors) {
        if (payload == null) {
            return;
        }
        if (payload.getExample() == null) {
            errors.add(prefix + ".example is malformed or empty (expected a JSON object)");
        }
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private ServiceRegistration toRegistration(ServiceManifest.ServiceSpec spec) {
        ServiceRegistration.ServiceRegistrationBuilder builder = ServiceRegistration.builder()
                .name(spec.getName().trim())
                .baseUrl(spec.getBaseUrl().trim())
                .namespace(spec.getNamespace())
                .description(spec.getDescription())
                .teamEmail(spec.getTeamEmail())
                .healthEndpoint(spec.getHealthEndpoint())
                .timeoutMs(spec.getTimeoutMs())
                .retryCount(spec.getRetryCount());

        if (spec.getAllowedCallerOrigins() != null) {
            builder.allowedCallerOrigins(new ArrayList<>(spec.getAllowedCallerOrigins()));
        }

        if (spec.getAuth() != null && !isBlank(spec.getAuth().getType())) {
            builder.authType(ServiceRegistration.AuthType.valueOf(
                            spec.getAuth().getType().trim().toUpperCase(Locale.ROOT)))
                    .authHeaderName(spec.getAuth().getHeaderName())
                    .authSecretRef(spec.getAuth().getSecretRef());
        }
        return builder.build();
    }

    private void registerContractIfPresent(String serviceName, String endpoint, String method,
                                           String direction, String version, String description,
                                           PayloadSpec payload, List<String> contractLines) {
        if (payload == null || payload.getExample() == null) {
            return;
        }
        ServiceContract contract = ServiceContract.builder()
                .serviceName(serviceName)
                .endpoint(endpoint)
                .httpMethod(method)
                .direction(direction)
                .version(version)
                .description(description)
                .registeredBy("manifest-import")
                .examplePayload(payload.getExample())
                .build();
        registryService.registerContract(contract);
        contractLines.add(direction + " " + method + " " + endpoint + " (v" + version + ")");
    }

    private void upsertRoute(String source, String target, String endpoint, String method,
                             String matchType, String description, List<String> routeLines) {
        ServiceRoute route = routeRepository
                .findBySourceServiceAndTargetServiceAndEndpointAndHttpMethod(source, target, endpoint, method)
                .orElseGet(ServiceRoute::new);
        route.setSourceService(source);
        route.setTargetService(target);
        route.setEndpoint(endpoint);
        route.setHttpMethod(method);
        route.setMatchType(matchType);
        route.setDescription(description);
        route.setActive(true);
        routeRepository.save(route);
        routeLines.add(source + " -> " + target + " " + method + " " + endpoint);
    }

    // ── Small utils ──────────────────────────────────────────────────────────

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        String trimmed = endpoint.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static String upper(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean looksLikeInlineSecret(String secretRef) {
        if (isBlank(secretRef)) {
            return false;
        }
        String s = secretRef.trim();
        // Heuristic: env var names are UPPER_SNAKE; tokens often start with these prefixes or contain spaces.
        return s.startsWith("Bearer ") || s.startsWith("eyJ") || s.startsWith("sk-")
                || s.startsWith("Basic ") || s.contains(" ");
    }
}
