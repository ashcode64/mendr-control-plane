package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.model.ServiceRoute;
import com.selfhealing.gateway.repository.OpenApiSpecRegistryRepository;
import com.selfhealing.gateway.repository.ServiceContractRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenApiImportServiceTest {

    @Mock private ServiceRegistryService registryService;
    @Mock private ServiceRouteRepository routeRepository;
    @Mock private ServiceContractRepository contractRepository;
    @Mock private OpenApiSpecRegistryRepository specRegistryRepository;
    @Mock private RouteChangedPublisher routeChangedPublisher;
    @Mock private TopologyGraphWriter topologyGraphWriter;

    private OpenApiImportService service;

    private static final String OAS = """
            openapi: 3.0.3
            info:
              title: Payment Service
              version: 1.0.0
            x-mendr-service: payment-service
            x-mendr-source: order-service
            x-mendr-host: payments.mendr.edge.net
            x-mendr-enforce: observe
            servers:
              - url: http://payment-service:8091
            paths:
              /api/payments/{id}:
                get:
                  summary: Get payment
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                    - name: include
                      in: query
                      schema:
                        type: string
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            required: [id, amount]
                            properties:
                              id:
                                type: string
                              amount:
                                type: number
              /api/payments:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            amount:
                              type: number
                  responses:
                    '201':
                      description: created
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: string
            """;

    @BeforeEach
    void setUp() {
        OpenApiFetchGuard fetchGuard = new OpenApiFetchGuard(registryService, specRegistryRepository);
        service = new OpenApiImportService(
                registryService, routeRepository, contractRepository,
                specRegistryRepository, routeChangedPublisher, fetchGuard, topologyGraphWriter);
    }

    @Test
    void dryRunDoesNotPersist() {
        var result = service.dryRun(OAS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isDryRun()).isTrue();
        assertThat(result.getServiceName()).isEqualTo("payment-service");
        assertThat(result.getRoutesCreated()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void importCreatesTemplateRouteAndDeclaredContracts() {
        when(specRegistryRepository.findBySourceAppAndSpecHashAndIsActiveTrue(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(routeRepository.findBySourceServiceAndTargetServiceAndEndpointAndHttpMethod(
                anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(routeRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(routeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contractRepository.findByServiceNameAndEndpointAndHttpMethodAndDirectionAndVersion(
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(specRegistryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registryService.register(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.importSpec(OAS);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSpecHash()).isNotBlank();

        ArgumentCaptor<ServiceRegistration> regCap = ArgumentCaptor.forClass(ServiceRegistration.class);
        verify(registryService).register(regCap.capture());
        assertThat(regCap.getValue().getName()).isEqualTo("payment-service");
        assertThat(regCap.getValue().getBaseUrl()).isEqualTo("http://payment-service:8091");

        ArgumentCaptor<ServiceRoute> routeCap = ArgumentCaptor.forClass(ServiceRoute.class);
        verify(routeRepository, org.mockito.Mockito.atLeastOnce()).save(routeCap.capture());
        assertThat(routeCap.getAllValues()).anyMatch(r ->
                "TEMPLATE".equals(r.getMatchType()) && r.getEndpoint().contains("{id}"));

        ArgumentCaptor<ServiceContract> contractCap = ArgumentCaptor.forClass(ServiceContract.class);
        verify(contractRepository, org.mockito.Mockito.atLeastOnce()).save(contractCap.capture());
        assertThat(contractCap.getAllValues()).anyMatch(c ->
                "OPENAPI_DECLARED".equals(c.getSchemaSource())
                        && c.getAllowedSurface() != null);
    }
}
