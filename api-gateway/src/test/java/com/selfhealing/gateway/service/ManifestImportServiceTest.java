package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.dto.manifest.ManifestImportResult;
import com.selfhealing.gateway.dto.manifest.ManifestValidationException;
import com.selfhealing.gateway.dto.manifest.ServiceManifest;
import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.model.ServiceRoute;
import com.selfhealing.gateway.repository.ServiceRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManifestImportServiceTest {

    @Mock private ServiceRegistryService registryService;
    @Mock private ServiceRouteRepository routeRepository;
    @Mock private RouteChangedPublisher routeChangedPublisher;

    private ManifestImportService importService;

    private static final String YAML = """
            apiVersion: mendr/v1
            kind: ServiceManifest
            service:
              name: order-service
              baseUrl: http://order-service:8090
              namespace: default
              description: Handles orders
              auth:
                type: JWT_BEARER
                headerName: Authorization
                secretRef: ORDER_SERVICE_TOKEN
            inbound:
              - endpoint: /api/orders
                method: POST
                request:
                  example:
                    customerId: "CUS-1"
                response:
                  example:
                    orderId: "ORD-1"
            outbound:
              - targetService: payment-service
                endpoint: /api/payments/charge
                method: POST
                matchType: EXACT
                description: Charge customer
                request:
                  example:
                    amount: 99.99
            """;

    @BeforeEach
    void setUp() {
        importService = new ManifestImportService(
                registryService, routeRepository, routeChangedPublisher, new ObjectMapper());
        lenient().when(routeRepository.save(any(ServiceRoute.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void parsesYamlManifest() {
        ServiceManifest manifest = importService.parse(YAML);

        assertThat(manifest.getService().getName()).isEqualTo("order-service");
        assertThat(manifest.getInbound()).hasSize(1);
        assertThat(manifest.getOutbound()).hasSize(1);
        assertThat(manifest.getOutbound().get(0).getTargetService()).isEqualTo("payment-service");
    }

    @Test
    void parsesJsonManifest() {
        String json = """
                {"apiVersion":"mendr/v1","service":{"name":"json-svc","baseUrl":"http://json-svc:8080"},
                 "outbound":[{"targetService":"payment-service","endpoint":"/api/pay","method":"POST"}]}
                """;

        ServiceManifest manifest = importService.parse(json);

        assertThat(manifest.getService().getName()).isEqualTo("json-svc");
        assertThat(manifest.getOutbound().get(0).getEndpoint()).isEqualTo("/api/pay");
    }

    @Test
    void importRegistersServiceContractsAndRoutes() {
        when(routeRepository.findBySourceServiceAndTargetServiceAndEndpointAndHttpMethod(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        ManifestImportResult result = importService.importManifest(YAML);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getService()).isEqualTo("order-service");
        assertThat(result.getRoutesCreated()).isEqualTo(1);
        // inbound REQUEST + RESPONSE, outbound REQUEST = 3 contracts
        assertThat(result.getContractsCreated()).isEqualTo(3);

        ArgumentCaptor<ServiceRegistration> regCaptor = ArgumentCaptor.forClass(ServiceRegistration.class);
        verify(registryService).register(regCaptor.capture());
        assertThat(regCaptor.getValue().getAuthType()).isEqualTo(ServiceRegistration.AuthType.JWT_BEARER);

        ArgumentCaptor<ServiceRoute> routeCaptor = ArgumentCaptor.forClass(ServiceRoute.class);
        verify(routeRepository).save(routeCaptor.capture());
        assertThat(routeCaptor.getValue().getSourceService()).isEqualTo("order-service");
        assertThat(routeCaptor.getValue().getTargetService()).isEqualTo("payment-service");
        assertThat(routeCaptor.getValue().getEndpoint()).isEqualTo("/api/payments/charge");

        verify(registryService, times(3)).registerContract(any(ServiceContract.class));
        verify(routeChangedPublisher).publishAll();
    }

    @Test
    void importIsIdempotentOnExistingRoute() {
        ServiceRoute existing = ServiceRoute.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/charge")
                .httpMethod("POST")
                .isActive(true)
                .build();
        when(routeRepository.findBySourceServiceAndTargetServiceAndEndpointAndHttpMethod(
                eq("order-service"), eq("payment-service"), eq("/api/payments/charge"), eq("POST")))
                .thenReturn(Optional.of(existing));

        importService.importManifest(YAML);

        ArgumentCaptor<ServiceRoute> routeCaptor = ArgumentCaptor.forClass(ServiceRoute.class);
        verify(routeRepository).save(routeCaptor.capture());
        // Same row reused (idempotent upsert), description updated.
        assertThat(routeCaptor.getValue()).isSameAs(existing);
        assertThat(routeCaptor.getValue().getDescription()).isEqualTo("Charge customer");
    }

    @Test
    void rejectsManifestMissingServiceName() {
        String bad = """
                service:
                  baseUrl: http://x:8080
                """;

        assertThatThrownBy(() -> importService.importManifest(bad))
                .isInstanceOf(ManifestValidationException.class)
                .satisfies(e -> assertThat(((ManifestValidationException) e).getErrors())
                        .anyMatch(s -> s.contains("service.name")));

        verify(registryService, never()).register(any());
        verify(routeChangedPublisher, never()).publishAll();
    }

    @Test
    void rejectsOutboundMissingTargetService() {
        String bad = """
                service:
                  name: order-service
                  baseUrl: http://order-service:8090
                outbound:
                  - endpoint: /api/payments/charge
                    method: POST
                """;

        assertThatThrownBy(() -> importService.importManifest(bad))
                .isInstanceOf(ManifestValidationException.class)
                .satisfies(e -> assertThat(((ManifestValidationException) e).getErrors())
                        .anyMatch(s -> s.contains("targetService")));
    }

    @Test
    void rejectsSelfReferentialOutbound() {
        String bad = """
                service:
                  name: order-service
                  baseUrl: http://order-service:8090
                outbound:
                  - targetService: order-service
                    endpoint: /api/loop
                    method: POST
                """;

        assertThatThrownBy(() -> importService.importManifest(bad))
                .isInstanceOf(ManifestValidationException.class)
                .satisfies(e -> assertThat(((ManifestValidationException) e).getErrors())
                        .anyMatch(s -> s.contains("self-referential")));
    }

    @Test
    void rejectsUnsupportedMatchType() {
        String bad = """
                service:
                  name: order-service
                  baseUrl: http://order-service:8090
                outbound:
                  - targetService: payment-service
                    endpoint: /api/payments/{id}
                    method: GET
                    matchType: TEMPLATE
                """;

        assertThatThrownBy(() -> importService.importManifest(bad))
                .isInstanceOf(ManifestValidationException.class)
                .satisfies(e -> assertThat(((ManifestValidationException) e).getErrors())
                        .anyMatch(s -> s.contains("not supported")));
    }

    @Test
    void rejectsUnknownAuthType() {
        String bad = """
                service:
                  name: order-service
                  baseUrl: http://order-service:8090
                  auth:
                    type: MAGIC
                """;

        assertThatThrownBy(() -> importService.importManifest(bad))
                .isInstanceOf(ManifestValidationException.class)
                .satisfies(e -> assertThat(((ManifestValidationException) e).getErrors())
                        .anyMatch(s -> s.contains("auth.type")));
    }

    @Test
    void warnsOnInlineSecretButStillImports() {
        String manifest = """
                service:
                  name: order-service
                  baseUrl: http://order-service:8090
                  auth:
                    type: JWT_BEARER
                    secretRef: "Bearer eyJhbGciOi"
                """;

        ManifestImportResult result = importService.importManifest(manifest);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("inline secret"));
    }

    @Test
    void normalizesEndpointLeadingSlash() {
        when(routeRepository.findBySourceServiceAndTargetServiceAndEndpointAndHttpMethod(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        String manifest = """
                service:
                  name: order-service
                  baseUrl: http://order-service:8090
                outbound:
                  - targetService: payment-service
                    endpoint: api/payments/charge
                    method: POST
                """;

        importService.importManifest(manifest);

        ArgumentCaptor<ServiceRoute> routeCaptor = ArgumentCaptor.forClass(ServiceRoute.class);
        verify(routeRepository).save(routeCaptor.capture());
        assertThat(routeCaptor.getValue().getEndpoint()).isEqualTo("/api/payments/charge");
    }
}
