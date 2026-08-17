package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.RouteConfigSnapshot;
import com.selfhealing.gateway.model.ServiceInstance;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.RateLimitPolicyRepository;
import com.selfhealing.gateway.repository.ServiceInstanceRepository;
import com.selfhealing.gateway.repository.ServiceRegistrationRepository;
import com.selfhealing.gateway.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayPolicyOverlayServiceTest {

    @Mock private ServiceRegistrationRepository serviceRegistrationRepository;
    @Mock private ServiceInstanceRepository serviceInstanceRepository;
    @Mock private RateLimitPolicyRepository rateLimitPolicyRepository;
    @Mock private TenantRepository tenantRepository;

    @InjectMocks
    private GatewayPolicyOverlayService overlayService;

    @Test
    void overlayProjectsWafIpGeoAndHealthPath() {
        UUID serviceId = UUID.randomUUID();
        ServiceRegistration reg = ServiceRegistration.builder()
                .id(serviceId)
                .name("orders")
                .healthEndpoint("/health")
                .retryPolicyJson(Map.of(
                        "waf", Map.of(
                                "mode", "block",
                                "ipAllow", List.of("10.0.0.0/8"),
                                "geoDeny", List.of("XX")
                        )))
                .build();
        when(serviceRegistrationRepository.findByNameAndIsActiveTrue("orders"))
                .thenReturn(Optional.of(reg));
        when(serviceInstanceRepository.findByServiceIdAndIsActiveTrueOrderByWeightDesc(serviceId))
                .thenReturn(List.of(ServiceInstance.builder()
                        .baseUrl("https://orders-1:8443")
                        .weight(100)
                        .healthStatus("UP")
                        .build()));

        RouteConfigSnapshot snap = RouteConfigSnapshot.builder()
                .targetService("orders")
                .build();
        overlayService.overlay(snap, "orders", "/v1/orders");

        assertThat(snap.getHealthEndpoint()).isEqualTo("/health");
        assertThat(snap.getWafPolicy()).isNotNull();
        assertThat(snap.getWafPolicy().getIpAllow()).containsExactly("10.0.0.0/8");
        assertThat(snap.getWafPolicy().getGeoDeny()).containsExactly("XX");
        assertThat(snap.getTargetInstances()).hasSize(1);
        assertThat(snap.getTargetInstances().getFirst().getHealthPath()).isEqualTo("/health");
        assertThat(snap.getTargetInstances().getFirst().getBaseUrl()).isEqualTo("https://orders-1:8443");
    }

    @Test
    void overlayProjectsCanaryAndVersioning() {
        UUID serviceId = UUID.randomUUID();
        ServiceRegistration reg = ServiceRegistration.builder()
                .id(serviceId)
                .name("orders")
                .healthEndpoint("/health")
                .retryPolicyJson(Map.of(
                        "traffic", Map.of(
                                "canaryPercent", 10,
                                "canaryInstances", List.of(Map.of("baseUrl", "https://canary:8443", "weight", 100)),
                                "mirrorPercent", 5,
                                "mirrorInstances", List.of(Map.of("baseUrl", "https://shadow:8443"))
                        ),
                        "versioning", Map.of(
                                "apiVersion", "v2",
                                "deprecated", true,
                                "sunsetAt", "Wed, 01 Jan 2027 00:00:00 GMT",
                                "successorEndpoint", "/v3/orders"
                        )))
                .build();
        when(serviceRegistrationRepository.findByNameAndIsActiveTrue("orders"))
                .thenReturn(Optional.of(reg));
        when(serviceInstanceRepository.findByServiceIdAndIsActiveTrueOrderByWeightDesc(serviceId))
                .thenReturn(List.of());

        RouteConfigSnapshot snap = RouteConfigSnapshot.builder().targetService("orders").build();
        overlayService.overlay(snap, "orders", "/v1/orders");

        assertThat(snap.getTrafficPolicy().getCanaryPercent()).isEqualTo(10);
        assertThat(snap.getTrafficPolicy().getCanaryInstances()).hasSize(1);
        assertThat(snap.getTrafficPolicy().getMirrorPercent()).isEqualTo(5);
        assertThat(snap.getVersioning()).isNotNull();
        assertThat(snap.getVersioning().getApiVersion()).isEqualTo("v2");
        assertThat(snap.getVersioning().isDeprecated()).isTrue();
    }
}
