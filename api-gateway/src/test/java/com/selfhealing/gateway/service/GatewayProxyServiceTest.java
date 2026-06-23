package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.config.GatewayFastPathProperties;
import com.selfhealing.gateway.dto.ProxyRequest;
import com.selfhealing.gateway.model.ApiFailure;
import com.selfhealing.gateway.model.RouteConfig;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.transform.StreamingProxyClient;
import com.selfhealing.gateway.transform.TransformProgram;
import com.selfhealing.gateway.util.ProxyEnvelopeParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayProxyServiceTest {

    @Mock private TransformationEngine requestEngine;
    @Mock private ResponseTransformationEngine responseEngine;
    @Mock private ServiceRegistryService registry;
    @Mock private DynamicRoutingService routingService;
    @Mock private DynamicCorsService corsService;
    @Mock private ResponseContractValidator responseValidator;
    @Mock private FailureIngestionService failureIngestionService;
    @Mock private RestTemplate restTemplate;
    @Mock private RouteConfigService routeConfigService;
    @Mock private ProxyEnvelopeParser envelopeParser;
    @Mock private StreamingProxyClient streamingProxyClient;

    private GatewayProxyService gatewayProxyService;
    private GatewayFastPathProperties fastPathProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProxyRequest proxyRequest;
    private RouteConfig fastPathConfig;

    @BeforeEach
    void setUp() throws Exception {
        fastPathProperties = new GatewayFastPathProperties();
        fastPathProperties.setStreamingTransformsEnabled(true);

        gatewayProxyService = new GatewayProxyService(
                requestEngine, responseEngine, registry, routingService, corsService,
                responseValidator, failureIngestionService,
                restTemplate, routeConfigService, envelopeParser, objectMapper,
                streamingProxyClient, fastPathProperties);

        proxyRequest = ProxyRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .method("POST")
                .payload(Map.of("amount", 100))
                .build();

        fastPathConfig = RouteConfig.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .targetBaseUrl("http://payment-service")
                .registeredBaseUrl(null)
                .authType(ServiceRegistration.AuthType.NONE)
                .hasRequestRules(false)
                .requestRules(List.of())
                .hasResponseRules(false)
                .responseRules(List.of())
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false)
                .build();
    }

    @Test
    void routingFailureDelegatesToFailureIngestionService() {
        String attemptedUrl = "http://payment-service/api/payments/process";

        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(fastPathConfig);
        when(restTemplate.exchange(
                eq(attemptedUrl), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        UUID failureId = UUID.randomUUID();
        when(failureIngestionService.recordRoutingFailure(
                eq(proxyRequest), eq(attemptedUrl), eq(null), anyString()))
                .thenReturn(ApiFailure.builder().id(failureId).build());

        ResponseEntity<Map<String, Object>> response = gatewayProxyService.proxy(proxyRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("failureId", failureId);
        verify(failureIngestionService).recordRoutingFailure(
                eq(proxyRequest), eq(attemptedUrl), eq(null), anyString());
    }

    @Test
    void routingFailurePassesAttemptedUrlToIngestionService() {
        String attemptedUrl = "http://payment-service/api/payments/process";

        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(fastPathConfig);
        when(restTemplate.exchange(
                eq(attemptedUrl), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("I/O error"));
        when(failureIngestionService.recordRoutingFailure(any(), any(), any(), anyString()))
                .thenReturn(ApiFailure.builder().id(UUID.randomUUID()).build());

        gatewayProxyService.proxy(proxyRequest);

        verify(failureIngestionService).recordRoutingFailure(
                eq(proxyRequest), eq(attemptedUrl), eq(null), anyString());
    }

    @Test
    void happyPathDoesNotCallLiveRegistryLookup() throws Exception {
        String targetUrl = "http://payment-service/api/payments/process";

        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(fastPathConfig);
        when(restTemplate.exchange(
                eq(targetUrl), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}".getBytes()));

        ResponseEntity<Map<String, Object>> response = gatewayProxyService.proxy(proxyRequest);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(registry, never()).loadRegisteredBaseUrl(anyString());
        verify(failureIngestionService, never()).recordRoutingFailure(any(), any(), any(), anyString());
        verify(streamingProxyClient, never()).forward(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void streamingPathUsesStreamingClientForFlatRules() throws Exception {
        TransformProgram renameProgram = TransformProgram.builder()
                .empty(false)
                .streamable(true)
                .renames(Map.of("amt", "amount"))
                .defaults(Map.of())
                .coercions(Map.of())
                .removals(Set.of())
                .build();

        RouteConfig streamConfig = RouteConfig.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .targetBaseUrl("http://localhost:8091")
                .authType(ServiceRegistration.AuthType.NONE)
                .hasRequestRules(true)
                .requestRules(List.of())
                .requestProgram(renameProgram)
                .hasResponseRules(false)
                .responseRules(List.of())
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false)
                .build();

        String targetUrl = "http://localhost:8091/api/payments/process";

        when(routeConfigService.get("order-service", "payment-service", "/api/payments/process"))
                .thenReturn(streamConfig);
        when(streamingProxyClient.forward(
                eq("POST"), eq(targetUrl), any(), any(), eq(renameProgram), any()))
                .thenReturn(new StreamingProxyClient.StreamResult(
                        200, "{\"status\":\"ok\"}".getBytes(), Map.of()));

        ResponseEntity<Map<String, Object>> response = gatewayProxyService.proxy(proxyRequest);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(streamingProxyClient).forward(
                eq("POST"), eq(targetUrl), any(), any(), eq(renameProgram), any());
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(byte[].class));
        verify(requestEngine, never()).applyTransformations(anyString(), anyString(), anyString(), any());
    }
}
