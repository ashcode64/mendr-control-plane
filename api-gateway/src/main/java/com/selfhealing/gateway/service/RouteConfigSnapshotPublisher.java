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

import com.selfhealing.gateway.tenant.TenantContext;
import com.selfhealing.gateway.tenant.TenantKeys;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Physical, tenant-namespaced Redis key for the per-tenant sync version. */
    private static String syncVersionKey() {
        return TenantKeys.scoped(SYNC_VERSION_KEY);
    }

    private final RouteConfigService routeConfigService;
    private final RouteProgramService routeProgramService;
    private final InterServiceRouteDiscovery routeDiscovery;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayInternalProperties internalProperties;
    private final GatewayOpenRestyProperties openRestyProperties;
    private final RouteSyncMetrics syncMetrics;

    /** Edge capability token: advertises support for snapshot v2 {@code ops[]} (MendrScript). */
    public static final String CAP_V2 = "v2";

    private final Object pendingSyncLock = new Object();

    // Per-tenant sync state: each tenant gets its own waiter list and known-key
    // set so one tenant's publish never wakes another's edge or mis-computes
    // removals against a different tenant's route set. Each waiter carries the
    // edge-advertised capabilities (v1/v2) so the payload is tailored per edge.
    private final Map<UUID, List<PendingSync>> pendingSyncsByTenant = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> knownRouteKeysByTenant = new ConcurrentHashMap<>();

    private List<PendingSync> pendingSyncs() {
        return pendingSyncsByTenant.computeIfAbsent(
                TenantContext.currentOrDefault(), k -> new CopyOnWriteArrayList<>());
    }

    private Set<String> knownRouteKeys() {
        return knownRouteKeysByTenant.computeIfAbsent(
                TenantContext.currentOrDefault(), k -> new HashSet<>());
    }

    /** A long-poll waiter together with the capabilities the requesting edge advertised. */
    private record PendingSync(Set<String> caps,
                               DeferredResult<ResponseEntity<RouteConfigSyncPayload>> deferred) {}

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
        String val = stringRedisTemplate.opsForValue().get(syncVersionKey());
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
        return buildFullSyncPayload(Set.of());
    }

    /**
     * Build the sync payload tailored to an edge's advertised capabilities (Gap 10).
     * The snapshot itself is dual-shape (legacy buckets AND {@code ops[]}), so a v1
     * edge transparently ignores {@code ops[]} and a v2 edge prefers it. The one
     * unsafe case is a v2-ONLY route (a DSL program: {@code ops[]} present, buckets
     * empty) served to a v1 edge — that would silently apply nothing. Such routes are
     * WITHHELD from non-v2 edges and an alert is logged, rather than shipping a
     * no-op that hides a needed heal. Withheld routes are treated like a transient
     * build failure for removal-tracking so they are never evicted.
     */
    public RouteConfigSyncPayload buildFullSyncPayload(Set<String> caps) {
        boolean v2 = caps != null && caps.contains(CAP_V2);
        Map<String, String> routes = new LinkedHashMap<>();
        Set<String> currentKeys = new HashSet<>();
        // Routes whose source still exists but whose snapshot build failed this run.
        // These must NOT be treated as "removed" — a transient build error must
        // never evict a route the edge is actively healing.
        Set<String> failedKeys = new HashSet<>();

        for (RouteTriple triple : routeDiscovery.discoverAll()) {
            String key = redisKey(triple.source(), triple.target(), triple.endpoint());
            try {
                RouteConfig config = routeConfigService.get(
                        triple.source(), triple.target(), triple.endpoint());
                RouteConfigSnapshot snapshot = toSnapshot(config);
                routeProgramService.ensureFreshMaterializedProgram(
                        triple.source(), triple.target(), triple.endpoint());
                overlayMaterializedProgram(snapshot, triple.source(), triple.target(), triple.endpoint());
                snapshot.setSyncValidation(isSyncValidationRoute(
                        triple.source(), triple.target(), triple.endpoint()));
                applyDockerHostRewrite(snapshot);

                if (!v2 && isV2OnlyRoute(snapshot)) {
                    // Capability mismatch: this edge cannot run the DSL program. Withhold +
                    // alert (do NOT evict) so an operator/heartbeat can flag the stale edge.
                    failedKeys.add(key);
                    log.warn("Withholding v2-only route {} from a legacy(v1) edge — the edge must "
                            + "advertise '{}' to receive the MendrScript program", key, CAP_V2);
                    continue;
                }

                routes.put(key, objectMapper.writeValueAsString(snapshot));
                currentKeys.add(key);
            } catch (JsonProcessingException e) {
                failedKeys.add(key);
                log.warn("Failed to serialize sync snapshot for {}:{}:{} — {}",
                        triple.source(), triple.target(), triple.endpoint(), e.getMessage());
            } catch (Exception e) {
                failedKeys.add(key);
                log.warn("Failed to build sync snapshot for {}:{}:{} — {}",
                        triple.source(), triple.target(), triple.endpoint(), e.getMessage());
            }
        }

        List<String> removed = new ArrayList<>();
        synchronized (pendingSyncLock) {
            Set<String> knownRouteKeys = knownRouteKeys();
            for (String key : knownRouteKeys) {
                // Only remove a key when the route is genuinely gone from discovery —
                // never merely because this run failed to (re)build its snapshot.
                if (!currentKeys.contains(key) && !failedKeys.contains(key)) {
                    removed.add(key);
                }
            }
            // Keep previously-known keys whose build failed this run so they are
            // re-evaluated (and not silently dropped) on the next sync.
            knownRouteKeys.clear();
            knownRouteKeys.addAll(currentKeys);
            knownRouteKeys.addAll(failedKeys);
        }

        return RouteConfigSyncPayload.builder()
                .version(currentConfigVersion())
                .routes(routes)
                .removed(removed)
                .build();
    }

    public void registerPendingSync(long since, DeferredResult<ResponseEntity<RouteConfigSyncPayload>> deferred) {
        registerPendingSync(since, Set.of(), deferred);
    }

    public void registerPendingSync(long since, Set<String> caps,
                                    DeferredResult<ResponseEntity<RouteConfigSyncPayload>> deferred) {
        synchronized (pendingSyncLock) {
            if (since < currentConfigVersion()) {
                deferred.setResult(ResponseEntity.ok(buildFullSyncPayload(caps)));
                return;
            }
            pendingSyncs().add(new PendingSync(caps == null ? Set.of() : caps, deferred));
        }
    }

    public void publishRoute(String sourceService, String targetService, String endpoint) {
        republishRouteInternal(sourceService, targetService, endpoint, false);
    }

    /** Publish without bumping sync version (caller bumps once after a batch). */
    public boolean publishRouteWithoutBump(String sourceService, String targetService, String endpoint) {
        return republishRouteInternal(sourceService, targetService, endpoint, false);
    }

    /**
     * Recompile, publish a single route snapshot, and bump the sync version so
     * long-polling edges are notified. Returns false when publish was skipped or failed.
     */
    public boolean republishRoute(String sourceService, String targetService, String endpoint) {
        return republishRouteInternal(sourceService, targetService, endpoint, true);
    }

    /** Bump the per-tenant sync version and wake long-polling edges. */
    public void bumpSyncVersionAndNotify() {
        bumpVersionAndNotifySyncWaiters();
    }

    /**
     * Republish all routes for a target service, then bump sync version once if any succeeded.
     */
    public boolean republishForService(String serviceName) {
        boolean anyPublished = false;
        for (RouteTriple r : routeDiscovery.discoverForService(serviceName)) {
            anyPublished |= republishRouteInternal(r.source(), r.target(), r.endpoint(), false);
        }
        if (anyPublished) {
            bumpVersionAndNotifySyncWaiters();
        }
        return anyPublished;
    }

    private boolean republishRouteInternal(String sourceService, String targetService, String endpoint,
                                           boolean bumpVersion) {
        // Recompile the materialized program first so the snapshot reflects the
        // current rule set atomically. If recompile fails its integrity guard
        // (empty program while active rules exist), DO NOT publish — leave the
        // last good snapshot in place rather than blanking a healed route.
        boolean recompiled = false;
        try {
            routeProgramService.recompileRoute(sourceService, targetService, endpoint, "route-changed");
            recompiled = true;
        } catch (RouteProgramService.RouteProgramIntegrityException e) {
            log.error("Skipping publish for {}:{}:{} — {}", sourceService, targetService, endpoint, e.getMessage());
            return false;
        } catch (com.selfhealing.gateway.transform.TransformProgramConflictException e) {
            log.error("Skipping publish for {}:{}:{} — conflicting rules: {}",
                    sourceService, targetService, endpoint, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Recompile failed for {}:{}:{} ({}). Attempting freshness repair before publish.",
                    sourceService, targetService, endpoint, e.getMessage());
        }
        if (!recompiled) {
            boolean repaired = routeProgramService.ensureFreshMaterializedProgram(sourceService, targetService, endpoint);
            if (!repaired && routeProgramService.isDrifted(sourceService, targetService, endpoint)) {
                log.warn("Skipping publish for {}:{}:{} - materialized program is still drifted after refresh attempt",
                        sourceService, targetService, endpoint);
                return false;
            }
        }
        try {
            RouteConfig config = routeConfigService.get(sourceService, targetService, endpoint);
            RouteConfigSnapshot snapshot = toSnapshot(config);
            overlayMaterializedProgram(snapshot, sourceService, targetService, endpoint);
            snapshot.setSyncValidation(isSyncValidationRoute(sourceService, targetService, endpoint));
            applyDockerHostRewrite(snapshot);

            String redisKey = redisKey(sourceService, targetService, endpoint);
            String physicalKey = TenantKeys.scoped(redisKey);
            String json = objectMapper.writeValueAsString(snapshot);

            int ttlSeconds = internalProperties.getRouteConfigSnapshotTtlSeconds();
            if (ttlSeconds > 0) {
                stringRedisTemplate.opsForValue().set(physicalKey, json, Duration.ofSeconds(ttlSeconds));
            } else {
                stringRedisTemplate.opsForValue().set(physicalKey, json);
            }
            log.debug("Published route snapshot {}", physicalKey);
            if (bumpVersion) {
                bumpVersionAndNotifySyncWaiters();
            }
            return true;
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize route snapshot for {}:{}:{} — {}",
                    sourceService, targetService, endpoint, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Failed to publish route snapshot for {}:{}:{} — {}",
                    sourceService, targetService, endpoint, e.getMessage());
            return false;
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
        // Per-tenant sync-version counter (physical key is tenant-namespaced).
        stringRedisTemplate.opsForValue().increment(syncVersionKey());

        List<PendingSync> waiters;
        synchronized (pendingSyncLock) {
            List<PendingSync> pendingSyncs = pendingSyncs();
            waiters = new ArrayList<>(pendingSyncs);
            pendingSyncs.clear();
        }

        // Each waiter gets a payload tailored to the capabilities it advertised, so a
        // v1 edge never receives a v2-only route (and vice-versa). Built per distinct
        // capability set to avoid recomputing for identical edges.
        Map<Set<String>, RouteConfigSyncPayload> byCaps = new java.util.HashMap<>();
        for (PendingSync waiter : waiters) {
            RouteConfigSyncPayload payload = byCaps.computeIfAbsent(
                    waiter.caps(), this::buildFullSyncPayload);
            waiter.deferred().setResult(ResponseEntity.ok(payload));
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

    /**
     * Overlay the durable, materialized merged program onto the snapshot when one
     * exists. The assembled config still provides base URL / auth / CORS, but the
     * transform programs come from the materialized {@code route_program} row so a
     * transient per-publish compile can never blank a route that has approved rules.
     */
    private void overlayMaterializedProgram(RouteConfigSnapshot snapshot,
                                            String source, String target, String endpoint) {
        boolean activeRules = routeProgramService.hasActiveRules(source, target, endpoint);
        int activeCount = activeRules ? routeProgramService.countActiveRules(source, target, endpoint) : 0;
        // With zero active rules, the assembled snapshot's empty programs are the
        // source of truth. Never replay a stale materialized row from a prior
        // failed recompile into the edge snapshot.
        if (!activeRules) {
            return;
        }
        routeProgramService.find(source, target, endpoint).ifPresentOrElse(rp -> {
            if (RouteProgramService.isMaterializedEmpty(rp)) {
                syncMetrics.recordOverlayDrift();
                log.warn("Overlay drift: route {}:{}:{} has {} active rule(s) but materialized program is empty — "
                                + "keeping assembled program",
                        source, target, endpoint, activeCount);
                routeProgramService.ensureFreshMaterializedProgram(source, target, endpoint);
                routeProgramService.find(source, target, endpoint).ifPresent(fresh -> {
                    if (!RouteProgramService.isMaterializedEmpty(fresh)) {
                        applyMaterializedOverlay(snapshot, fresh, source, target, endpoint);
                    }
                });
                return;
            }
            applyMaterializedOverlay(snapshot, rp, source, target, endpoint);
        }, () -> {
            syncMetrics.recordOverlayDrift();
            log.warn("Overlay drift: route {}:{}:{} has {} active rule(s) but no materialized program row",
                    source, target, endpoint, activeCount);
            routeProgramService.ensureFreshMaterializedProgram(source, target, endpoint);
            routeProgramService.find(source, target, endpoint).ifPresent(fresh ->
                    applyMaterializedOverlay(snapshot, fresh, source, target, endpoint));
        });
    }

    private void applyMaterializedOverlay(RouteConfigSnapshot snapshot, com.selfhealing.gateway.model.RouteProgram rp,
                                          String source, String target, String endpoint) {
            try {
                snapshot.setProgramHash(rp.getProgramHash());
                if (rp.getRequestProgram() != null) {
                    snapshot.setRequestProgram(objectMapper.convertValue(
                            rp.getRequestProgram(), RouteConfigSnapshot.TransformProgramSnapshot.class));
                }
                if (rp.getResponseProgram() != null) {
                    snapshot.setResponseProgram(objectMapper.convertValue(
                            rp.getResponseProgram(), RouteConfigSnapshot.TransformProgramSnapshot.class));
                }
            } catch (Exception e) {
                log.warn("Failed to overlay materialized program for {}:{}:{} — {}",
                        source, target, endpoint, e.getMessage());
            }
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
                    .moves(java.util.List.of())
                    .build();
        }
        return TransformProgramSnapshot.builder()
                .empty(program.isEmpty())
                .streamable(program.isStreamable())
                .schemaVersion(program.getSchemaVersion() != null ? program.getSchemaVersion() : "v1")
                .ops(program.getOps())
                .renames(program.getRenames())
                .defaults(program.getDefaults())
                .coercions(program.getCoercions())
                .removals(program.getRemovals())
                .wrapKey(program.getWrapKey())
                .unwrapKey(program.getUnwrapKey())
                .moves(program.getMoves())
                .scales(program.getScales())
                .coalesce(program.getCoalesce())
                .valueMaps(program.getValueMaps())
                .dateFormats(program.getDateFormats())
                .stripUnknown(program.getStripUnknown())
                .wrapArrays(program.getWrapArrays())
                .unwrapArrays(program.getUnwrapArrays())
                .build();
    }

    /** A route is "v2-only" when either direction carries {@code ops[]} but no legacy ops. */
    static boolean isV2OnlyRoute(RouteConfigSnapshot snapshot) {
        return isV2OnlyProgram(snapshot.getRequestProgram())
                || isV2OnlyProgram(snapshot.getResponseProgram());
    }

    private static boolean isV2OnlyProgram(TransformProgramSnapshot p) {
        if (p == null || p.getOps() == null || p.getOps().isEmpty()) {
            return false;
        }
        return isEmpty(p.getRenames()) && isEmpty(p.getDefaults()) && isEmpty(p.getCoercions())
                && isEmpty(p.getRemovals()) && isEmpty(p.getMoves()) && isEmpty(p.getScales())
                && isEmpty(p.getCoalesce()) && isEmpty(p.getValueMaps()) && isEmpty(p.getDateFormats())
                && isEmpty(p.getStripUnknown()) && isEmpty(p.getWrapArrays()) && isEmpty(p.getUnwrapArrays())
                && p.getWrapKey() == null && p.getUnwrapKey() == null;
    }

    private static boolean isEmpty(Map<?, ?> m) { return m == null || m.isEmpty(); }
    private static boolean isEmpty(java.util.Collection<?> c) { return c == null || c.isEmpty(); }

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
