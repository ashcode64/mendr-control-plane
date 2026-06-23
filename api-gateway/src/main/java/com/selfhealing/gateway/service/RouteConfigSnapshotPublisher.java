package com.selfhealing.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.config.GatewayInternalProperties;
import com.selfhealing.gateway.config.GatewayOpenRestyProperties;
import com.selfhealing.gateway.dto.RouteConfigSnapshot;
import com.selfhealing.gateway.dto.RouteConfigSnapshot.TransformProgramSnapshot;
import com.selfhealing.gateway.dto.RouteConfigSyncPayload;
import com.selfhealing.gateway.model.RouteConfig;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.service.InterServiceRouteDiscovery.RouteTriple;
import com.selfhealing.gateway.transform.TransformProgram;
import com.selfhealing.gateway.util.DockerHostUrlRewriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Publishes plain JSON route snapshots to Redis for OpenResty/Lua and future Envoy Wasm.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteConfigSnapshotPublisher {

    public static final String REDIS_KEY_PREFIX = "mendr:routeconfig:";
    static final String SYNC_VERSION_KEY = "mendr:routeconfig:sync-version";

    private final RouteConfigService routeConfigService;
    private final InterServiceRouteDiscovery routeDiscovery;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayInternalProperties internalProperties;
    private final GatewayOpenRestyProperties openRestyProperties;

    private final Object pendingSyncLock = new Object();
    private final List<DeferredResult<ResponseEntity<RouteConfigSyncPayload>>> pendingSyncs =
            new CopyOnWriteArrayList<>();
    private final Set<String> knownRouteKeys = new HashSet<>();

    @EventListener(ApplicationReadyEvent.class)
    public void warmPublishOnStartup() {
        try {
            publishAllDistinctRoutes();
            log.info("Warm-published route config snapshots on startup");
            scheduleDelayedRepublish(15);
            scheduleDelayedRepublish(45);
        } catch (Exception e) {
            log.warn("Route snapshot warm publish failed: {}", e.getMessage());
        }
    }

    private void scheduleDelayedRepublish(int delaySeconds) {
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
            try {
                publishAllDistinctRoutes();
                log.info("Delayed route snapshot republish ({}s after startup)", delaySeconds);
            } catch (Exception e) {
                log.warn("Delayed route snapshot republish failed: {}", e.getMessage());
            }
        });
    }

    public void handleInvalidationMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if ("*".equals(message)) {
            publishAllDistinctRoutes();
            return;
        }
        if (message.startsWith("target:")) {
            publishForService(message.substring("target:".length()));
            return;
        }
        parseRouteKey(message).ifPresent(triple -> {
            publishRoute(triple.source(), triple.target(), triple.endpoint());
            bumpVersionAndNotifySyncWaiters();
        });
    }

    public long currentConfigVersion() {
        String val = stringRedisTemplate.opsForValue().get(SYNC_VERSION_KEY);
        if (val == null || val.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            log.warn("Invalid sync version in Redis: {}", val);
            return 0L;
        }
    }

    public RouteConfigSyncPayload buildFullSyncPayload() {
        Map<String, String> routes = new LinkedHashMap<>();
        Set<String> currentKeys = new HashSet<>();

        for (RouteTriple triple : routeDiscovery.discoverAll()) {
            try {
                RouteConfig config = routeConfigService.get(
                        triple.source(), triple.target(), triple.endpoint());
                RouteConfigSnapshot snapshot = toSnapshot(config);
                snapshot.setSyncValidation(isSyncValidationRoute(
                        triple.source(), triple.target(), triple.endpoint()));
                applyDockerHostRewrite(snapshot);

                String key = redisKey(triple.source(), triple.target(), triple.endpoint());
                routes.put(key, objectMapper.writeValueAsString(snapshot));
                currentKeys.add(key);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize sync snapshot for {}:{}:{} — {}",
                        triple.source(), triple.target(), triple.endpoint(), e.getMessage());
            } catch (Exception e) {
                log.warn("Failed to build sync snapshot for {}:{}:{} — {}",
                        triple.source(), triple.target(), triple.endpoint(), e.getMessage());
            }
        }

        List<String> removed = new ArrayList<>();
        synchronized (pendingSyncLock) {
            for (String key : knownRouteKeys) {
                if (!currentKeys.contains(key)) {
                    removed.add(key);
                }
            }
            knownRouteKeys.clear();
            knownRouteKeys.addAll(currentKeys);
        }

        return RouteConfigSyncPayload.builder()
                .version(currentConfigVersion())
                .routes(routes)
                .removed(removed)
                .build();
    }

    public void registerPendingSync(long since, DeferredResult<ResponseEntity<RouteConfigSyncPayload>> deferred) {
        synchronized (pendingSyncLock) {
            if (since < currentConfigVersion()) {
                deferred.setResult(ResponseEntity.ok(buildFullSyncPayload()));
                return;
            }
            pendingSyncs.add(deferred);
        }
    }

    public void publishRoute(String sourceService, String targetService, String endpoint) {
        try {
            RouteConfig config = routeConfigService.get(sourceService, targetService, endpoint);
            RouteConfigSnapshot snapshot = toSnapshot(config);
            snapshot.setSyncValidation(isSyncValidationRoute(sourceService, targetService, endpoint));
            applyDockerHostRewrite(snapshot);

            String redisKey = redisKey(sourceService, targetService, endpoint);
            String json = objectMapper.writeValueAsString(snapshot);

            int ttlSeconds = internalProperties.getRouteConfigSnapshotTtlSeconds();
            if (ttlSeconds > 0) {
                stringRedisTemplate.opsForValue().set(redisKey, json, Duration.ofSeconds(ttlSeconds));
            } else {
                stringRedisTemplate.opsForValue().set(redisKey, json);
            }
            log.debug("Published route snapshot {}", redisKey);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize route snapshot for {}:{}:{} — {}",
                    sourceService, targetService, endpoint, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to publish route snapshot for {}:{}:{} — {}",
                    sourceService, targetService, endpoint, e.getMessage());
        }
    }

    public void publishForService(String serviceName) {
        routeDiscovery.discoverForService(serviceName)
                .forEach(r -> publishRoute(r.source(), r.target(), r.endpoint()));
        bumpVersionAndNotifySyncWaiters();
    }

    public void publishAllDistinctRoutes() {
        routeDiscovery.discoverAll()
                .forEach(r -> publishRoute(r.source(), r.target(), r.endpoint()));
        bumpVersionAndNotifySyncWaiters();
    }

    private void bumpVersionAndNotifySyncWaiters() {
        stringRedisTemplate.opsForValue().increment(SYNC_VERSION_KEY);
        RouteConfigSyncPayload payload = buildFullSyncPayload();

        List<DeferredResult<ResponseEntity<RouteConfigSyncPayload>>> waiters;
        synchronized (pendingSyncLock) {
            waiters = new ArrayList<>(pendingSyncs);
            pendingSyncs.clear();
        }

        ResponseEntity<RouteConfigSyncPayload> response = ResponseEntity.ok(payload);
        for (DeferredResult<ResponseEntity<RouteConfigSyncPayload>> waiter : waiters) {
            waiter.setResult(response);
        }
    }

    private boolean isSyncValidationRoute(String source, String target, String endpoint) {
        List<String> patterns = internalProperties.getSyncValidationEndpoints();
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String exactKey = source + ":" + target + ":" + endpoint;
        String wildcardKey = "*:" + target + ":" + endpoint;
        for (String pattern : patterns) {
            if (pattern.equals(exactKey) || pattern.equals(wildcardKey)) {
                return true;
            }
        }
        return false;
    }

    private void applyDockerHostRewrite(RouteConfigSnapshot snapshot) {
        String rewrite = openRestyProperties.getDockerHostRewrite();
        if (rewrite == null || rewrite.isBlank()) {
            return;
        }
        snapshot.setTargetBaseUrl(DockerHostUrlRewriter.rewriteLocalHost(snapshot.getTargetBaseUrl(), rewrite));
        snapshot.setRegisteredBaseUrl(DockerHostUrlRewriter.rewriteLocalHost(snapshot.getRegisteredBaseUrl(), rewrite));
    }

    static String rewriteLocalHost(String url, String dockerHost) {
        return DockerHostUrlRewriter.rewriteLocalHost(url, dockerHost);
    }

    public static String redisKey(String sourceService, String targetService, String endpoint) {
        return REDIS_KEY_PREFIX + sourceService + ":" + targetService + ":" + endpoint;
    }

    static RouteConfigSnapshot toSnapshot(RouteConfig config) {
        String authType = config.getAuthType() != null
                ? config.getAuthType().name()
                : ServiceRegistration.AuthType.NONE.name();

        java.util.List<RouteConfigSnapshot.OriginOverrideSnapshot> overrides = java.util.List.of();
        if (config.getOriginOverrides() != null && !config.getOriginOverrides().isEmpty()) {
            overrides = config.getOriginOverrides().stream()
                    .map(o -> RouteConfigSnapshot.OriginOverrideSnapshot.builder()
                            .callerOriginMatch(o.callerOriginMatch())
                            .outboundOriginOverride(o.outboundOriginOverride())
                            .rewriteResponseAcao(o.rewriteResponseAcao())
                            .build())
                    .toList();
        }

        return RouteConfigSnapshot.builder()
                .sourceService(config.getSourceService())
                .targetService(config.getTargetService())
                .endpoint(config.getEndpoint())
                .targetBaseUrl(config.getTargetBaseUrl())
                .registeredBaseUrl(config.getRegisteredBaseUrl())
                .corsActive(config.isCorsActive())
                .allowedOrigins(config.getAllowedOrigins())
                .hasResponseContract(config.isHasResponseContract())
                .authType(authType)
                .authHeaderName(config.getAuthHeaderName())
                .requestProgram(toProgramSnapshot(config.getRequestProgram()))
                .responseProgram(toProgramSnapshot(config.getResponseProgram()))
                .originOverrides(overrides)
                .build();
    }

    private static TransformProgramSnapshot toProgramSnapshot(TransformProgram program) {
        if (program == null) {
            return TransformProgramSnapshot.builder()
                    .empty(true)
                    .streamable(true)
                    .renames(java.util.Map.of())
                    .defaults(java.util.Map.of())
                    .coercions(java.util.Map.of())
                    .removals(java.util.Set.of())
                    .build();
        }
        return TransformProgramSnapshot.builder()
                .empty(program.isEmpty())
                .streamable(program.isStreamable())
                .renames(program.getRenames())
                .defaults(program.getDefaults())
                .coercions(program.getCoercions())
                .removals(program.getRemovals())
                .wrapKey(program.getWrapKey())
                .unwrapKey(program.getUnwrapKey())
                .build();
    }

    private static java.util.Optional<RouteTriple> parseRouteKey(String routeKey) {
        int first = routeKey.indexOf(':');
        if (first < 0) {
            return java.util.Optional.empty();
        }
        int second = routeKey.indexOf(':', first + 1);
        if (second < 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new RouteTriple(
                routeKey.substring(0, first),
                routeKey.substring(first + 1, second),
                routeKey.substring(second + 1)));
    }
}
