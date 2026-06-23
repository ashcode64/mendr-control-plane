package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayOpenRestyProperties;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.ServiceRegistrationRepository;
import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.util.DockerHostUrlRewriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Central service registry — the single source of truth for where
 * every service lives and how to authenticate to it.
 *
 * Resolution order per request:
 *   1. Redis cache (hot path, ~μs)
 *   2. Active RoutingRule override (service moved)
 *   3. DB service registration
 *   4. k8s DNS fallback: name.namespace.svc.cluster.local:8080
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceRegistryService {

    private final ServiceRegistrationRepository serviceRepo;
    private final ServiceContractRepository contractRepo;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RestTemplate restTemplate;
    private final RouteChangedPublisher routeChangedPublisher;
    private final GatewayOpenRestyProperties openRestyProperties;
    private final DynamicCorsService dynamicCorsService;

    private static final String URL_CACHE_PREFIX  = "svc:url:";
    private static final String AUTH_CACHE_PREFIX = "svc:auth:";
    private static final long   CACHE_TTL_SECONDS = 120;

    // ── URL Resolution ────────────────────────────────────────────────────────

    public String resolveBaseUrl(String serviceName) {
        String cacheKey = URL_CACHE_PREFIX + serviceName;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return cached.toString();

        Optional<ServiceRegistration> reg = serviceRepo.findByNameAndIsActiveTrue(serviceName);
        if (reg.isPresent() && reg.get().getBaseUrl() != null) {
            String url = reg.get().getBaseUrl();
            redisTemplate.opsForValue().set(cacheKey, url, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return url;
        }

        // k8s DNS fallback: service.namespace.svc.cluster.local
        String k8sDns = buildK8sDns(serviceName, reg.map(ServiceRegistration::getNamespace).orElse("default"));
        log.debug("No base_url registered for '{}', falling back to k8s DNS: {}", serviceName, k8sDns);
        return k8sDns;
    }

    private String buildK8sDns(String serviceName, String namespace) {
        return "http://" + serviceName + "." + namespace + ".svc.cluster.local:8080";
    }

    // ── Auth Header Injection ─────────────────────────────────────────────────

    /**
     * Given a service name and the original request headers,
     * build the outbound headers with the correct auth credentials injected.
     *
     * Priority:
     *  1. If the caller already sent a matching auth header — pass it through unchanged
     *  2. Otherwise inject the service's own credential from the env var reference
     */
    public void injectAuthHeaders(HttpHeaders outbound, String targetService,
                                   Map<String, String> incomingHeaders) {

        Optional<ServiceRegistration> regOpt = serviceRepo.findByNameAndIsActiveTrue(targetService);
        if (regOpt.isEmpty()) return;

        ServiceRegistration reg = regOpt.get();
        if (reg.getAuthType() == ServiceRegistration.AuthType.NONE) return;

        switch (reg.getAuthType()) {
            case JWT_BEARER -> {
                // Pass through caller's JWT if present, otherwise inject service credential
                String headerName = reg.getAuthHeaderName() != null ? reg.getAuthHeaderName() : "Authorization";
                String existing   = incomingHeaders != null ? incomingHeaders.get(headerName) : null;
                if (existing != null && !existing.isBlank()) {
                    outbound.set(headerName, existing);
                } else if (reg.getAuthSecretRef() != null) {
                    String token = resolveSecret(reg.getAuthSecretRef());
                    if (token != null) outbound.set(headerName, "Bearer " + token);
                }
            }
            case API_KEY_HEADER -> {
                String headerName = reg.getAuthHeaderName() != null ? reg.getAuthHeaderName() : "X-Api-Key";
                String existing   = incomingHeaders != null ? incomingHeaders.get(headerName) : null;
                if (existing != null && !existing.isBlank()) {
                    outbound.set(headerName, existing);
                } else if (reg.getAuthSecretRef() != null) {
                    String key = resolveSecret(reg.getAuthSecretRef());
                    if (key != null) outbound.set(headerName, key);
                }
            }
            case API_KEY_QUERY -> {
                // Nothing to add to headers; query param handled in URL building
                log.debug("API_KEY_QUERY auth for {} — ensure query param is in the endpoint path", targetService);
            }
            case BASIC -> {
                if (reg.getAuthSecretRef() != null) {
                    // Secret ref should contain base64-encoded user:pass
                    String encoded = resolveSecret(reg.getAuthSecretRef());
                    if (encoded != null) outbound.set("Authorization", "Basic " + encoded);
                }
            }
        }
    }

    /**
     * Resolves the actual secret value from the environment.
     * The secret ref is just an env var name — never the secret itself in DB.
     */
    private String resolveSecret(String envVarName) {
        String value = System.getenv(envVarName);
        if (value == null || value.isBlank()) {
            log.warn("Secret env var '{}' is not set — auth header will be missing", envVarName);
        }
        return value;
    }

    // ── Header Passthrough Whitelist ──────────────────────────────────────────

    /**
     * Copy important headers from the incoming request to the outbound call.
     * These must never be dropped — tracing, correlation, tenant context, etc.
     */
    public void passthroughHeaders(HttpHeaders outbound, Map<String, String> incoming) {
        if (incoming == null) return;

        List<String> PASSTHROUGH = List.of(
            "Authorization",
            "X-Api-Key",
            "X-Correlation-Id",
            "X-Request-Id",
            "X-Trace-Id",
            "X-B3-TraceId",
            "X-B3-SpanId",
            "X-B3-ParentSpanId",
            "X-B3-Sampled",
            "X-Tenant-Id",
            "X-User-Id",
            "X-Forwarded-For",
            "traceparent",
            "tracestate"
        );

        incoming.forEach((key, value) -> {
            if (PASSTHROUGH.stream().anyMatch(k -> k.equalsIgnoreCase(key))) {
                outbound.set(key, value);
            }
        });
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Register or update a service. Re-registration always sets {@code isActive=true}
     * and updates the managed row in place so {@code base_url} reliably overwrites
     * stale seed data (e.g. k8s hostnames → localhost on local dev).
     */
    @Transactional
    public ServiceRegistration register(ServiceRegistration incoming) {
        ServiceRegistration target = serviceRepo.findByName(incoming.getName())
                .map(existing -> applyUpdates(existing, incoming))
                .orElse(incoming);
        target.setActive(true);
        ServiceRegistration saved = serviceRepo.saveAndFlush(target);
        evictCache(saved.getName());
        if (incoming.getAllowedCallerOrigins() != null) {
            dynamicCorsService.syncDeclaredOrigins(saved.getName(), incoming.getAllowedCallerOrigins());
        }
        log.info("Registered '{}' -> base_url={} active={} allowedCallerOrigins={}",
                saved.getName(), saved.getBaseUrl(), saved.isActive(), saved.getAllowedCallerOrigins());
        return saved;
    }

    /** Live DB read for routing-failure enrichment only — not used on happy path. */
    public Optional<String> loadRegisteredBaseUrl(String serviceName) {
        return serviceRepo.findByNameAndIsActiveTrue(serviceName)
                .map(ServiceRegistration::getBaseUrl)
                .filter(url -> url != null && !url.isBlank());
    }

    private ServiceRegistration applyUpdates(ServiceRegistration existing, ServiceRegistration incoming) {
        if (!isBlank(incoming.getBaseUrl())) existing.setBaseUrl(incoming.getBaseUrl());
        if (!isBlank(incoming.getDescription())) existing.setDescription(incoming.getDescription());
        if (!isBlank(incoming.getTeamEmail())) existing.setTeamEmail(incoming.getTeamEmail());
        if (!isBlank(incoming.getNamespace())) existing.setNamespace(incoming.getNamespace());
        if (!isBlank(incoming.getK8sServiceName())) existing.setK8sServiceName(incoming.getK8sServiceName());
        if (!isBlank(incoming.getHealthEndpoint())) existing.setHealthEndpoint(incoming.getHealthEndpoint());
        if (incoming.getAuthType() != null) existing.setAuthType(incoming.getAuthType());
        if (!isBlank(incoming.getAuthHeaderName())) existing.setAuthHeaderName(incoming.getAuthHeaderName());
        if (!isBlank(incoming.getAuthSecretRef())) existing.setAuthSecretRef(incoming.getAuthSecretRef());
        if (incoming.getTimeoutMs() != null) existing.setTimeoutMs(incoming.getTimeoutMs());
        if (incoming.getRetryCount() != null) existing.setRetryCount(incoming.getRetryCount());
        if (incoming.getAllowedCallerOrigins() != null) {
            existing.setAllowedCallerOrigins(new java.util.ArrayList<>(incoming.getAllowedCallerOrigins()));
        }
        return existing;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Register or update a schema contract. Idempotent on restart — matches the
     * UNIQUE (service_name, endpoint, http_method, direction, version) constraint.
     */
    public ServiceContract registerContract(ServiceContract contract) {
        String httpMethod = contract.getHttpMethod() != null ? contract.getHttpMethod() : "POST";
        String direction  = contract.getDirection()  != null ? contract.getDirection()  : "REQUEST";
        String version    = contract.getVersion()    != null ? contract.getVersion()    : "1.0";

        contract.setHttpMethod(httpMethod);
        contract.setDirection(direction);
        contract.setVersion(version);

        contractRepo
                .findByServiceNameAndEndpointAndHttpMethodAndDirectionAndVersion(
                        contract.getServiceName(), contract.getEndpoint(), httpMethod, direction, version)
                .ifPresent(existing -> contract.setId(existing.getId()));

        contract.setActive(true);
        ServiceContract saved = contractRepo.save(contract);
        routeChangedPublisher.publishAll();
        log.info("Registered contract for '{}' {}{} ({})",
                saved.getServiceName(), saved.getHttpMethod(), saved.getEndpoint(), saved.getDirection());
        return saved;
    }

    public List<ServiceRegistration> getAllServices() {
        return serviceRepo.findAllOrderedByName();
    }

    public Optional<ServiceRegistration> getService(String name) {
        return serviceRepo.findByName(name);
    }

    public void deactivate(String name) {
        serviceRepo.findByName(name).ifPresent(s -> {
            s.setActive(false);
            serviceRepo.save(s);
            evictCache(name);
        });
    }

    public void evictCache(String serviceName) {
        redisTemplate.delete(URL_CACHE_PREFIX + serviceName);
        redisTemplate.delete(AUTH_CACHE_PREFIX + serviceName);
        routeChangedPublisher.publishTargetService(serviceName);
    }

    // ── Scheduled health checks ───────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void healthCheckAll() {
        List<ServiceRegistration> services = serviceRepo.findAllByIsActiveTrue();
        for (ServiceRegistration svc : services) {
            String baseUrl   = svc.getBaseUrl();
            String healthPath = svc.getHealthEndpoint() != null ? svc.getHealthEndpoint() : "/actuator/health";
            if (baseUrl == null) continue;
            String healthUrl = DockerHostUrlRewriter.rewriteLocalHost(
                    baseUrl, openRestyProperties.getDockerHostRewrite()) + healthPath;
            try {
                restTemplate.getForEntity(healthUrl, String.class);
                svc.setLastHealthStatus("UP");
            } catch (Exception e) {
                svc.setLastHealthStatus("DOWN");
                log.warn("Health check failed for '{}': {}", svc.getName(), e.getMessage());
            }
            svc.setLastHealthCheck(LocalDateTime.now());
            serviceRepo.save(svc);
        }
    }
}
