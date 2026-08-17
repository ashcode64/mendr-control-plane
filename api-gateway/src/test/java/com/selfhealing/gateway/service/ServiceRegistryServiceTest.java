package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayOpenRestyProperties;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.repository.ServiceInstanceRepository;
import com.selfhealing.gateway.repository.ServiceRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceRegistryServiceTest {

    @Mock private ServiceRegistrationRepository serviceRepo;
    @Mock private ServiceInstanceRepository serviceInstanceRepository;
    @Mock private ServiceContractRepository contractRepo;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private RestTemplate restTemplate;
    @Mock private RouteChangedPublisher routeChangedPublisher;
    @Mock private GatewayOpenRestyProperties openRestyProperties;
    @Mock private DynamicCorsService dynamicCorsService;

    private ServiceRegistryService registryService;

    private UUID existingId;
    private LocalDateTime createdAt;

    @BeforeEach
    void setUp() {
        existingId = UUID.randomUUID();
        createdAt = LocalDateTime.now().minusDays(1);
        registryService = new ServiceRegistryService(
                serviceRepo, serviceInstanceRepository, contractRepo, redisTemplate, restTemplate,
                routeChangedPublisher, openRestyProperties, dynamicCorsService);
    }

    @Test
    void reRegistrationWithoutIsActivePreservesActiveState() {
        ServiceRegistration existing = ServiceRegistration.builder()
                .id(existingId)
                .name("payment-service")
                .baseUrl("http://localhost:8091")
                .description("Payments")
                .namespace("default")
                .authType(ServiceRegistration.AuthType.NONE)
                .timeoutMs(10000)
                .retryCount(2)
                .isActive(true)
                .createdAt(createdAt)
                .build();

        ServiceRegistration incoming = ServiceRegistration.builder()
                .name("payment-service")
                .baseUrl("http://localhost:8091")
                .build();

        when(serviceRepo.findByName("payment-service")).thenReturn(Optional.of(existing));
        when(serviceRepo.saveAndFlush(any(ServiceRegistration.class))).thenAnswer(inv -> inv.getArgument(0));

        ServiceRegistration saved = registryService.register(incoming);

        ArgumentCaptor<ServiceRegistration> captor = ArgumentCaptor.forClass(ServiceRegistration.class);
        verify(serviceRepo).saveAndFlush(captor.capture());

        assertThat(saved.isActive()).isTrue();
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(captor.getValue().getId()).isEqualTo(existingId);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(createdAt);
        assertThat(captor.getValue().getDescription()).isEqualTo("Payments");
    }

    @Test
    void reRegistrationOverwritesStaleBaseUrl() {
        ServiceRegistration existing = ServiceRegistration.builder()
                .id(existingId)
                .name("payment-service")
                .baseUrl("http://payment-service:8092")
                .description("Payment processing and refunds")
                .isActive(true)
                .createdAt(createdAt)
                .build();

        ServiceRegistration incoming = ServiceRegistration.builder()
                .name("payment-service")
                .baseUrl("http://localhost:8091")
                .description("Processes payments. Strict camelCase schema.")
                .build();

        when(serviceRepo.findByName("payment-service")).thenReturn(Optional.of(existing));
        when(serviceRepo.saveAndFlush(any(ServiceRegistration.class))).thenAnswer(inv -> inv.getArgument(0));

        ServiceRegistration saved = registryService.register(incoming);

        assertThat(saved.getBaseUrl()).isEqualTo("http://localhost:8091");
        assertThat(saved.isActive()).isTrue();
        assertThat(existing.getBaseUrl()).isEqualTo("http://localhost:8091");
    }

    @Test
    void loadRegisteredBaseUrlReturnsActiveServiceBaseUrl() {
        ServiceRegistration active = ServiceRegistration.builder()
                .name("payment-service")
                .baseUrl("http://localhost:8091")
                .isActive(true)
                .build();

        when(serviceRepo.findByNameAndIsActiveTrue("payment-service")).thenReturn(Optional.of(active));

        Optional<String> url = registryService.loadRegisteredBaseUrl("payment-service");

        assertThat(url).contains("http://localhost:8091");
    }

    @Test
    void healthCheckUsesDockerHostRewrite() {
        ServiceRegistration svc = ServiceRegistration.builder()
                .name("payment-service")
                .baseUrl("http://localhost:8091")
                .healthEndpoint("/actuator/health")
                .isActive(true)
                .build();

        when(serviceRepo.findAllByIsActiveTrue()).thenReturn(List.of(svc));
        when(serviceRepo.save(any(ServiceRegistration.class))).thenAnswer(inv -> inv.getArgument(0));
        when(openRestyProperties.getDockerHostRewrite()).thenReturn("host.docker.internal");

        registryService.healthCheckAll();

        verify(restTemplate).getForEntity(
                eq("http://host.docker.internal:8091/actuator/health"), eq(String.class));
        assertThat(svc.getLastHealthStatus()).isEqualTo("UP");
    }

    @Test
    void loadRegisteredBaseUrlReturnsEmptyWhenInactiveOrMissing() {
        when(serviceRepo.findByNameAndIsActiveTrue("payment-service")).thenReturn(Optional.empty());

        assertThat(registryService.loadRegisteredBaseUrl("payment-service")).isEmpty();
    }

    @Test
    void registerWithAllowedCallerOriginsSyncsCorsPolicy() {
        ServiceRegistration incoming = ServiceRegistration.builder()
                .name("payment-service")
                .baseUrl("http://localhost:8091")
                .allowedCallerOrigins(List.of("http://localhost:8090"))
                .build();

        when(serviceRepo.findByName("payment-service")).thenReturn(Optional.empty());
        when(serviceRepo.saveAndFlush(any(ServiceRegistration.class))).thenAnswer(inv -> inv.getArgument(0));

        registryService.register(incoming);

        verify(dynamicCorsService).syncDeclaredOrigins(
                "payment-service", List.of("http://localhost:8090"));
    }
}
