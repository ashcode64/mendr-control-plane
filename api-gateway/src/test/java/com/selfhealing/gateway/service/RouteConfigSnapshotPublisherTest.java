package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.config.GatewayInternalProperties;
import com.selfhealing.gateway.config.GatewayOpenRestyProperties;
import com.selfhealing.gateway.model.RouteConfig;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.dto.RouteConfigSnapshot;
import com.selfhealing.gateway.service.InterServiceRouteDiscovery.RouteTriple;
import com.selfhealing.gateway.transform.TransformProgram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteConfigSnapshotPublisherTest {

    @Mock private RouteConfigService routeConfigService;
    @Mock private RouteProgramService routeProgramService;
    @Mock private InterServiceRouteDiscovery routeDiscovery;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private com.selfhealing.gateway.repository.ServiceRouteRepository serviceRouteRepository;
    @Mock private com.selfhealing.gateway.repository.ServiceContractRepository serviceContractRepository;
    @Mock private com.selfhealing.gateway.repository.OpenApiSpecRegistryRepository openApiSpecRegistryRepository;
    @Mock private IngressHostIdentityService ingressHostIdentityService;
    @Mock private GatewayPolicyOverlayService gatewayPolicyOverlayService;
    @Mock private EdgeCapabilityTracker edgeCapabilityTracker;

    private RouteConfigSnapshotPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private RouteSyncMetrics syncMetrics;

    // Physical Redis keys are namespaced per tenant; with no bound context the
    // publisher falls back to the default tenant.
    private static final String TENANT_NS = "t:00000000-0000-0000-0000-000000000001:";

    @BeforeEach
    void setUp() {
        GatewayInternalProperties internalProperties = new GatewayInternalProperties();
        GatewayOpenRestyProperties openRestyProperties = new GatewayOpenRestyProperties();
        publisher = new RouteConfigSnapshotPublisher(
                routeConfigService,
                routeProgramService,
                routeDiscovery,
                stringRedisTemplate,
                objectMapper,
                internalProperties,
                openRestyProperties,
                syncMetrics,
                serviceRouteRepository,
                serviceContractRepository,
                openApiSpecRegistryRepository,
                ingressHostIdentityService,
                gatewayPolicyOverlayService,
                edgeCapabilityTracker);
    }

    @Test
    void publishRouteWritesPlainJsonWithoutTypeMetadata() throws Exception {
        TransformProgram program = TransformProgram.builder()
                .empty(false)
                .streamable(true)
                .renames(Map.of("amt", "amount"))
                .defaults(Map.of())
                .coercions(Map.of())
                .removals(Set.of())
                .build();

        RouteConfig config = RouteConfig.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .targetBaseUrl("http://localhost:8091")
                .registeredBaseUrl("http://localhost:8091")
                .authType(ServiceRegistration.AuthType.NONE)
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false)
                .requestProgram(program)
                .responseProgram(TransformProgram.none())
                .build();

        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(config);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        publisher.publishRoute("order-service", "payment-service", "/api/payments/process");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(TENANT_NS + "mendr:routeconfig:order-service:payment-service:/api/payments/process"),
                jsonCaptor.capture());

        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.has("@class")).isFalse();
        assertThat(root.get("targetBaseUrl").asText()).isEqualTo("http://localhost:8091");
        assertThat(root.get("requestProgram").get("renames").get("amt").asText()).isEqualTo("amount");
    }

    @Test
    void publishRouteRewritesLocalhostForDockerOpenResty() throws Exception {
        GatewayOpenRestyProperties openRestyProperties = new GatewayOpenRestyProperties();
        openRestyProperties.setDockerHostRewrite("host.docker.internal");

        RouteConfigSnapshotPublisher dockerPublisher = new RouteConfigSnapshotPublisher(
                routeConfigService,
                routeProgramService,
                routeDiscovery,
                stringRedisTemplate,
                objectMapper,
                new GatewayInternalProperties(),
                openRestyProperties,
                syncMetrics,
                serviceRouteRepository,
                serviceContractRepository,
                openApiSpecRegistryRepository,
                ingressHostIdentityService,
                gatewayPolicyOverlayService,
                edgeCapabilityTracker);

        RouteConfig config = RouteConfig.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .targetBaseUrl("http://localhost:8091")
                .registeredBaseUrl("http://localhost:8091")
                .authType(ServiceRegistration.AuthType.NONE)
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false)
                .build();

        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(config);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        dockerPublisher.publishRoute("order-service", "payment-service", "/api/payments/process");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(
                TENANT_NS + "mendr:routeconfig:order-service:payment-service:/api/payments/process"),
                jsonCaptor.capture());

        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.get("targetBaseUrl").asText()).isEqualTo("http://host.docker.internal:8091");
    }

    @Test
    void handleInvalidationForRouteKeyRepublishesSingleRoute() {
        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(RouteConfig.builder()
                        .sourceService("order-service")
                        .targetService("payment-service")
                        .endpoint("/api/payments/process")
                        .targetBaseUrl("http://localhost:8091")
                        .authType(ServiceRegistration.AuthType.NONE)
                        .corsActive(false)
                        .allowedOrigins(Set.of())
                        .hasResponseContract(false)
                        .build());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        publisher.handleInvalidationMessage("order-service:payment-service:/api/payments/process");

        verify(routeConfigService).get("order-service", "payment-service", "/api/payments/process");
    }

    @Test
    void publishRouteSkipsStaleRepublishWhenRefreshStillDrifted() {
        when(routeProgramService.recompileRoute(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("rls"));
        when(routeProgramService.ensureFreshMaterializedProgram("inventory-service", "shipping-service", "/ship"))
                .thenReturn(false);
        when(routeProgramService.isDrifted("inventory-service", "shipping-service", "/ship"))
                .thenReturn(true);

        boolean published = publisher.publishRouteWithoutBump("inventory-service", "shipping-service", "/ship");

        assertThat(published).isFalse();
        verify(routeConfigService, never()).get(anyString(), anyString(), anyString());
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    void publishForServiceUsesRouteDiscovery() {
        when(routeDiscovery.discoverForService("payment-service")).thenReturn(Set.of(
                new RouteTriple("order-service", "payment-service", "/api/payments/process")));
        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(RouteConfig.builder()
                        .sourceService("order-service")
                        .targetService("payment-service")
                        .endpoint("/api/payments/process")
                        .targetBaseUrl("http://localhost:8091")
                        .authType(ServiceRegistration.AuthType.NONE)
                        .corsActive(false)
                        .allowedOrigins(Set.of())
                        .hasResponseContract(false)
                        .build());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        publisher.publishForService("payment-service");

        verify(routeDiscovery).discoverForService("payment-service");
        verify(routeConfigService).get("order-service", "payment-service", "/api/payments/process");
    }

    @Test
    void publishRouteIncludesOriginOverridesInSnapshot() throws Exception {
        RouteConfig config = RouteConfig.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .targetBaseUrl("http://localhost:8091")
                .registeredBaseUrl("http://localhost:8091")
                .authType(ServiceRegistration.AuthType.NONE)
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false)
                .originOverrides(List.of(
                        new RouteConfig.OriginOverrideSpec(
                                "http://order-service-v2:9090",
                                "http://localhost:8090",
                                true)))
                .build();

        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(config);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        publisher.publishRoute("order-service", "payment-service", "/api/payments/process");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(TENANT_NS + "mendr:routeconfig:order-service:payment-service:/api/payments/process"),
                jsonCaptor.capture());

        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.get("originOverrides")).isNotNull();
        assertThat(root.get("originOverrides")).hasSize(1);
        assertThat(root.get("originOverrides").get(0).get("callerOriginMatch").asText())
                .isEqualTo("http://order-service-v2:9090");
        assertThat(root.get("originOverrides").get(0).get("outboundOriginOverride").asText())
                .isEqualTo("http://localhost:8090");
        assertThat(root.get("originOverrides").get(0).get("rewriteResponseAcao").asBoolean()).isTrue();
    }

    @Test
    void buildFullSyncPayloadIncludesDiscoveredManifestRoute() {
        when(routeDiscovery.discoverAll()).thenReturn(Set.of(
                new RouteTriple("order-service", "payment-service", "/api/payments/charge")));
        when(routeConfigService.get("order-service", "payment-service", "/api/payments/charge"))
                .thenReturn(RouteConfig.builder()
                        .sourceService("order-service")
                        .targetService("payment-service")
                        .endpoint("/api/payments/charge")
                        .targetBaseUrl("http://localhost:8091")
                        .registeredBaseUrl("http://localhost:8091")
                        .authType(ServiceRegistration.AuthType.NONE)
                        .corsActive(false)
                        .allowedOrigins(Set.of())
                        .hasResponseContract(false)
                        .build());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(eq(TENANT_NS + "mendr:routeconfig:sync-version"))).thenReturn("3");

        var payload = publisher.buildFullSyncPayload();

        // The logical route map key (what the edge consumes) stays un-namespaced.
        assertThat(payload.getRoutes())
                .containsKey("mendr:routeconfig:order-service:payment-service:/api/payments/charge");
    }

    @Test
    void rewriteLocalHostHandles127AndLocalhost() {
        assertThat(RouteConfigSnapshotPublisher.rewriteLocalHost(
                "http://localhost:8091", "host.docker.internal"))
                .isEqualTo("http://host.docker.internal:8091");
        assertThat(RouteConfigSnapshotPublisher.rewriteLocalHost(
                "http://127.0.0.1:8091", "host.docker.internal"))
                .isEqualTo("http://host.docker.internal:8091");
    }

    @Test
    void overlaySkipsStaleMaterializedProgramWhenNoActiveRulesRemain() throws Exception {
        java.util.Map<String, Object> staleReq = new java.util.LinkedHashMap<>();
        staleReq.put("empty", false);
        staleReq.put("ops", java.util.List.of(
                java.util.Map.of("op", "move", "from", "/obj_id/item_id/new_id", "to", "/tag_sent")));
        java.util.Map<String, Object> emptyReq = java.util.Map.of("empty", true, "ops", java.util.List.of());

        com.selfhealing.gateway.model.RouteProgram stale =
                com.selfhealing.gateway.model.RouteProgram.builder()
                        .requestProgram(staleReq)
                        .responseProgram(emptyReq)
                        .programHash("stalehash")
                        .ruleCount(1)
                        .build();

        when(routeProgramService.recompileRoute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RouteProgramService.RecompileResult(true, 2, 0));
        when(routeProgramService.hasActiveRules("inventory-service", "shipping-service", "/ship"))
                .thenReturn(false);

        RouteConfig config = RouteConfig.builder()
                .sourceService("inventory-service")
                .targetService("shipping-service")
                .endpoint("/ship")
                .targetBaseUrl("http://localhost:8002")
                .authType(ServiceRegistration.AuthType.NONE)
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false)
                .requestProgram(TransformProgram.none())
                .responseProgram(TransformProgram.none())
                .build();

        when(routeConfigService.get("inventory-service", "shipping-service", "/ship")).thenReturn(config);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        publisher.publishRoute("inventory-service", "shipping-service", "/ship");

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(TENANT_NS + "mendr:routeconfig:inventory-service:shipping-service:/ship"),
                jsonCaptor.capture());

        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.path("programHash").isMissingNode() || root.path("programHash").isNull()).isTrue();
        assertThat(root.get("requestProgram").get("empty").asBoolean()).isTrue();
        assertThat(root.get("requestProgram").get("ops")).isEmpty();
    }

    @Test
    void overlayDoesNotBlankWhenMaterializedEmptyButActiveRulesExist() throws Exception {
        java.util.Map<String, Object> emptyReq = java.util.Map.of("empty", true, "ops", java.util.List.of());
        java.util.Map<String, Object> fixedReq = new java.util.LinkedHashMap<>();
        fixedReq.put("empty", false);
        fixedReq.put("ops", java.util.List.of(
                java.util.Map.of("op", "move", "from", "/obj_id/item_id/new_id", "to", "/tag_sent")));

        com.selfhealing.gateway.model.RouteProgram stale =
                com.selfhealing.gateway.model.RouteProgram.builder()
                        .requestProgram(emptyReq)
                        .responseProgram(emptyReq)
                        .programHash("stale")
                        .ruleCount(0)
                        .build();
        com.selfhealing.gateway.model.RouteProgram fixed =
                com.selfhealing.gateway.model.RouteProgram.builder()
                        .requestProgram(fixedReq)
                        .responseProgram(emptyReq)
                        .programHash("fixedhash")
                        .ruleCount(1)
                        .build();

        when(routeProgramService.recompileRoute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RouteProgramService.RecompileResult(true, 2, 1));
        when(routeProgramService.hasActiveRules("inventory-service", "shipping-service", "/ship"))
                .thenReturn(true);
        when(routeProgramService.countActiveRules("inventory-service", "shipping-service", "/ship"))
                .thenReturn(1);
        when(routeProgramService.find("inventory-service", "shipping-service", "/ship"))
                .thenReturn(java.util.Optional.of(stale), java.util.Optional.of(fixed));

        RouteConfig config = RouteConfig.builder()
                .sourceService("inventory-service")
                .targetService("shipping-service")
                .endpoint("/ship")
                .targetBaseUrl("http://localhost:8002")
                .authType(ServiceRegistration.AuthType.NONE)
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false)
                .requestProgram(TransformProgram.none())
                .build();

        when(routeConfigService.get("inventory-service", "shipping-service", "/ship")).thenReturn(config);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        publisher.publishRoute("inventory-service", "shipping-service", "/ship");

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(TENANT_NS + "mendr:routeconfig:inventory-service:shipping-service:/ship"),
                jsonCaptor.capture());

        JsonNode root = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(root.get("programHash").asText()).isEqualTo("fixedhash");
        assertThat(root.get("requestProgram").get("ops")).hasSize(1);
        verify(syncMetrics).recordOverlayDrift();
    }

    @Test
    void stripSpliceFieldsForcesStreamableFalseWhenOpsPresent() {
        RouteConfigSnapshot.TransformProgramSnapshot program =
                RouteConfigSnapshot.TransformProgramSnapshot.builder()
                        .empty(false)
                        .streamable(true)
                        .ops(List.of(Map.of("op", "rename", "from", "/amt", "to", "/amount")))
                        .planClass("PREFILTERABLE")
                        .build();
        RouteConfigSnapshot snapshot = RouteConfigSnapshot.builder()
                .responseProgram(program)
                .build();
        RouteConfigSnapshotPublisher.stripSpliceFields(snapshot);
        assertThat(snapshot.getResponseProgram().getPlanClass()).isNull();
        assertThat(snapshot.getResponseProgram().isStreamable()).isFalse();
    }
}
