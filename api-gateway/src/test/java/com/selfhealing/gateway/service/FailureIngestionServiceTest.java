package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayInternalProperties;
import com.selfhealing.gateway.dto.ApiFailureEvent;
import com.selfhealing.gateway.dto.IngestFailureRequest;
import com.selfhealing.gateway.dto.ProxyRequest;
import com.selfhealing.gateway.model.ApiFailure;
import com.selfhealing.gateway.repository.ApiFailureRepository;
import com.selfhealing.gateway.service.FailureIngestionService.IngestOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FailureIngestionServiceTest {

    @Mock private ApiFailureRepository failureRepository;
    @Mock private KafkaTemplate<String, ApiFailureEvent> kafkaTemplate;
    @Mock private ServiceRegistryService registry;
    @Mock private DynamicRoutingService routingService;
    @Mock private DnsProbeService dnsProbeService;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private GatewayInternalProperties internalProperties;
    private FailureIngestionService failureIngestionService;

    @BeforeEach
    void setUp() {
        internalProperties = new GatewayInternalProperties();
        internalProperties.setFailureDedupTtlSeconds(60);
        failureIngestionService = new FailureIngestionService(
                failureRepository, kafkaTemplate, registry, routingService, dnsProbeService,
                stringRedisTemplate, internalProperties);
    }

    @Test
    void recordRoutingFailureEnrichesFromLiveRegistryAndDnsProbe() {
        ProxyRequest request = proxyRequest();
        String attemptedUrl = "http://payment-service/api/payments/process";

        when(registry.loadRegisteredBaseUrl("payment-service"))
                .thenReturn(Optional.of("http://localhost:8091"));
        when(dnsProbeService.discoverNewUrl("payment-service", attemptedUrl))
                .thenReturn(Optional.of("http://localhost:8091"));
        when(failureRepository.save(any(ApiFailure.class))).thenAnswer(this::withRandomId);

        failureIngestionService.recordRoutingFailure(request, attemptedUrl, null, "Connection refused");

        ApiFailureEvent event = captureEvent();
        assertThat(event.getFailureCategory()).isEqualTo("ROUTING");
        assertThat(event.getAttemptedUrl()).isEqualTo(attemptedUrl);
        assertThat(event.getRegisteredBaseUrl()).isEqualTo("http://localhost:8091");
        assertThat(event.getDnsProbeDiscoveryUrl()).isEqualTo("http://localhost:8091");
    }

    @Test
    void ingestRoutingMatchesRecordRoutingEnrichment() {
        IngestFailureRequest request = IngestFailureRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .httpMethod("POST")
                .errorCode(503)
                .errorType("ROUTING_FAILURE")
                .failureCategory("ROUTING")
                .errorMessage("Connection refused")
                .attemptedUrl("http://payment-service/api/payments/process")
                .requestPayload(Map.of("amount", 100))
                .build();

        when(stringRedisTemplate.hasKey("mendr:fail-dedup:order-service:payment-service:/api/payments/process"))
                .thenReturn(false);
        when(registry.loadRegisteredBaseUrl("payment-service"))
                .thenReturn(Optional.of("http://localhost:8091"));
        when(dnsProbeService.discoverNewUrl(eq("payment-service"), any()))
                .thenReturn(Optional.of("http://localhost:8091"));
        when(failureRepository.save(any(ApiFailure.class))).thenAnswer(this::withRandomId);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        IngestOutcome outcome = failureIngestionService.ingest(request);

        assertThat(outcome.isDeduplicated()).isFalse();
        ApiFailureEvent event = captureEvent();
        assertThat(event.getFailureCategory()).isEqualTo("ROUTING");
        assertThat(event.getAttemptedUrl()).isEqualTo("http://payment-service/api/payments/process");
        assertThat(event.getTargetServiceUrl()).isEqualTo("http://payment-service/api/payments/process");
        assertThat(event.getRegisteredBaseUrl()).isEqualTo("http://localhost:8091");
        assertThat(event.getDnsProbeDiscoveryUrl()).isEqualTo("http://localhost:8091");
    }

    @Test
    void ingestCorsMatchesRecordCorsEventShape() {
        ProxyRequest proxy = proxyRequest();
        String origin = "http://order-service-v2:9090";
        String message = "CORS policy blocked origin '" + origin + "'";

        when(failureRepository.save(any(ApiFailure.class))).thenAnswer(this::withRandomId);
        failureIngestionService.recordCorsFailure(proxy, origin, 403, message);

        ArgumentCaptor<ApiFailureEvent> captor = ArgumentCaptor.forClass(ApiFailureEvent.class);
        verify(kafkaTemplate).send(eq("api.failures"), any(), captor.capture());
        ApiFailureEvent recordEvent = captor.getValue();

        IngestFailureRequest ingestRequest = IngestFailureRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .httpMethod("POST")
                .errorCode(403)
                .errorType("CORS_FAILURE")
                .failureCategory("CORS")
                .errorMessage(message)
                .requestOrigin(origin)
                .requestPayload(Map.of("amount", 100))
                .build();

        when(stringRedisTemplate.hasKey(any())).thenReturn(false);
        when(failureRepository.save(any(ApiFailure.class))).thenAnswer(this::withRandomId);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        failureIngestionService.ingest(ingestRequest);
        verify(kafkaTemplate, times(2)).send(eq("api.failures"), any(), captor.capture());
        ApiFailureEvent ingestEvent = captor.getAllValues().get(1);

        assertThat(ingestEvent.getFailureCategory()).isEqualTo(recordEvent.getFailureCategory());
        assertThat(ingestEvent.getRequestOrigin()).isEqualTo(recordEvent.getRequestOrigin());
        assertThat(ingestEvent.getErrorType()).isEqualTo(recordEvent.getErrorType());
        assertThat(ingestEvent.getErrorCode()).isEqualTo(recordEvent.getErrorCode());
    }

    @Test
    void ingestCorsUpstreamIncludesCorsBlockedAt() {
        String origin = "http://order-service-v2:9090";
        IngestFailureRequest request = IngestFailureRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .httpMethod("POST")
                .errorCode(403)
                .errorType("CORS_FAILURE")
                .failureCategory("CORS_UPSTREAM")
                .errorMessage("Upstream CORS rejected origin")
                .requestOrigin(origin)
                .corsBlockedAt("UPSTREAM")
                .requestPayload(Map.of("amount", 100))
                .build();

        when(stringRedisTemplate.hasKey(any())).thenReturn(false);
        when(registry.loadRegisteredBaseUrl("payment-service"))
                .thenReturn(Optional.of("http://localhost:8091"));
        when(failureRepository.save(any(ApiFailure.class))).thenAnswer(this::withRandomId);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        failureIngestionService.ingest(request);

        ApiFailureEvent event = captureEvent();
        assertThat(event.getFailureCategory()).isEqualTo("CORS_UPSTREAM");
        assertThat(event.getCorsBlockedAt()).isEqualTo("UPSTREAM");
        assertThat(event.getRequestOrigin()).isEqualTo(origin);
    }

    @Test
    void ingestSchemaMismatchIncludesRegisteredBaseAndResponsePayload() {
        IngestFailureRequest request = IngestFailureRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .httpMethod("POST")
                .errorCode(400)
                .errorType("SCHEMA_MISMATCH_FAILURE")
                .failureCategory("SCHEMA_MISMATCH")
                .errorMessage("amount is required")
                .attemptedUrl("http://host.docker.internal:8091/api/payments/process")
                .targetServiceUrl("http://host.docker.internal:8091/api/payments/process")
                .requestPayload(Map.of("customer_id", "CUST-1"))
                .responsePayload(Map.of("raw", Map.of("error", "amount is required")))
                .build();

        when(stringRedisTemplate.hasKey(any())).thenReturn(false);
        when(registry.loadRegisteredBaseUrl("payment-service"))
                .thenReturn(Optional.of("http://localhost:8091"));
        when(failureRepository.save(any(ApiFailure.class))).thenAnswer(this::withRandomId);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        failureIngestionService.ingest(request);

        ApiFailureEvent event = captureEvent();
        assertThat(event.getFailureCategory()).isEqualTo("SCHEMA_MISMATCH");
        assertThat(event.getRegisteredBaseUrl()).isEqualTo("http://localhost:8091");
        assertThat(event.getTargetServiceUrl()).isEqualTo("http://host.docker.internal:8091/api/payments/process");
        assertThat(event.getResponsePayload()).containsKey("raw");
    }

    @Test
    void firstFailureIngestEscalatesSecondIsDeduplicated() {
        IngestFailureRequest request = IngestFailureRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .httpMethod("POST")
                .errorCode(400)
                .errorType("SCHEMA_MISMATCH_FAILURE")
                .failureCategory("SCHEMA_MISMATCH")
                .errorMessage("bad schema")
                .build();

        when(stringRedisTemplate.hasKey("mendr:fail-dedup:order-service:payment-service:/api/payments/process"))
                .thenReturn(false, true);
        when(registry.loadRegisteredBaseUrl("payment-service")).thenReturn(Optional.empty());
        when(failureRepository.save(any(ApiFailure.class))).thenAnswer(this::withRandomId);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        IngestOutcome first = failureIngestionService.ingest(request);
        IngestOutcome second = failureIngestionService.ingest(request);

        assertThat(first.isDeduplicated()).isFalse();
        assertThat(first.failureId()).isNotNull();
        assertThat(second.isDeduplicated()).isTrue();
        verify(kafkaTemplate, times(1)).send(eq("api.failures"), any(), any());
        verify(valueOperations).set(
                eq("mendr:fail-dedup:order-service:payment-service:/api/payments/process"),
                eq("1"),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    void deduplicatedIngestDoesNotPersistOrPublish() {
        IngestFailureRequest request = IngestFailureRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .failureCategory("SCHEMA_MISMATCH")
                .errorCode(400)
                .build();

        when(stringRedisTemplate.hasKey(any())).thenReturn(true);

        IngestOutcome outcome = failureIngestionService.ingest(request);

        assertThat(outcome.isDeduplicated()).isTrue();
        verify(failureRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    private static ProxyRequest proxyRequest() {
        return ProxyRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .method("POST")
                .payload(Map.of("amount", 100))
                .build();
    }

    private ApiFailure withRandomId(org.mockito.invocation.InvocationOnMock inv) {
        ApiFailure failure = inv.getArgument(0);
        failure.setId(UUID.randomUUID());
        return failure;
    }

    private ApiFailureEvent captureEvent() {
        ArgumentCaptor<ApiFailureEvent> captor = ArgumentCaptor.forClass(ApiFailureEvent.class);
        verify(kafkaTemplate).send(eq("api.failures"), any(), captor.capture());
        return captor.getValue();
    }
}
