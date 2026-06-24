package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.config.GatewayInternalProperties;
import com.selfhealing.gateway.config.GatewayOpenRestyProperties;
import com.selfhealing.gateway.model.RouteConfig;
import com.selfhealing.gateway.model.ServiceRegistration;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteConfigSnapshotPublisherTest {

    @Mock private RouteConfigService routeConfigService;
    @Mock private InterServiceRouteDiscovery routeDiscovery;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RouteConfigSnapshotPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GatewayInternalProperties internalProperties = new GatewayInternalProperties();
        GatewayOpenRestyProperties openRestyProperties = new GatewayOpenRestyProperties();
        publisher = new RouteConfigSnapshotPublisher(
                routeConfigService,
                routeDiscovery,
                stringRedisTemplate,
                objectMapper,
                internalProperties,
                openRestyProperties);
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
                eq("mendr:routeconfig:order-service:payment-service:/api/payments/process"),
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
                routeDiscovery,
                stringRedisTemplate,
                objectMapper,
                new GatewayInternalProperties(),
                openRestyProperties);

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
                "mendr:routeconfig:order-service:payment-service:/api/payments/process"),
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
                eq("mendr:routeconfig:order-service:payment-service:/api/payments/process"),
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
        when(valueOperations.get(eq("mendr:routeconfig:sync-version"))).thenReturn("3");

        var payload = publisher.buildFullSyncPayload();

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
}
