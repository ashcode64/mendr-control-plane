package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayInternalProperties;
import com.selfhealing.gateway.dto.ValidateResponseRequest;
import com.selfhealing.gateway.model.ApiFailure;
import com.selfhealing.gateway.util.ResponseMismatchAnalyzer.ResponseMismatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResponseValidationServiceTest {

    @Mock private ResponseContractValidator responseValidator;
    @Mock private FailureIngestionService failureIngestionService;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private ResponseValidationService responseValidationService;

    @BeforeEach
    void setUp() {
        GatewayInternalProperties properties = new GatewayInternalProperties();
        properties.setValidateDedupTtlSeconds(60);
        responseValidationService = new ResponseValidationService(
                responseValidator, failureIngestionService, stringRedisTemplate, properties);
    }

    @Test
    void firstMismatchEscalatesAndSetsDedupKey() {
        ValidateResponseRequest request = ValidateResponseRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .httpMethod("POST")
                .transformedResponse(Map.of("status", "bad"))
                .rawResponse(Map.of("status", "ok"))
                .build();

        when(stringRedisTemplate.hasKey("t:00000000-0000-0000-0000-000000000001:mendr:validate-dedup:order-service:payment-service:/api/payments/process"))
                .thenReturn(false);
        when(responseValidator.validate("order-service", "payment-service", "/api/payments/process",
                request.getTransformedResponse()))
                .thenReturn(Optional.of(new ResponseMismatch(
                        ResponseMismatch.Kind.MISSING_FIELD,
                        "missing amount",
                        Set.of("amount"),
                        Map.of(),
                        Map.of(),
                        Map.of())));

        UUID failureId = UUID.randomUUID();
        when(failureIngestionService.recordResponseMismatch(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ApiFailure.builder().id(failureId).build());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        var outcome = responseValidationService.validate(request);

        assertThat(outcome.status()).isEqualTo("mismatch");
        assertThat(outcome.failureId()).isEqualTo(failureId);
        verify(valueOperations).set(
                eq("t:00000000-0000-0000-0000-000000000001:mendr:validate-dedup:order-service:payment-service:/api/payments/process"),
                eq("1"),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    void secondReportWithinTtlIsDeduplicated() {
        ValidateResponseRequest request = ValidateResponseRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .transformedResponse(Map.of("status", "bad"))
                .build();

        when(stringRedisTemplate.hasKey("t:00000000-0000-0000-0000-000000000001:mendr:validate-dedup:order-service:payment-service:/api/payments/process"))
                .thenReturn(true);

        var outcome = responseValidationService.validate(request);

        assertThat(outcome.status()).isEqualTo("deduplicated");
        verify(responseValidator, never()).validate(any(), any(), any(), any());
        verify(failureIngestionService, never()).recordResponseMismatch(any(), any(), any(), any());
    }

    @Test
    void mismatchPassThroughProblemDetailAndCorrelationTelemetry() {
        Map<String, Object> problemDetail = Map.of(
                "type", "https://example.com/problems/response",
                "title", "Mismatch",
                "status", 502,
                "detail", "missing amount",
                "json_path", "/amount"
        );
        Map<String, Object> responseHeaders = Map.of("Content-Type", "application/problem+json");

        ValidateResponseRequest request = ValidateResponseRequest.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/process")
                .httpMethod("POST")
                .transformedResponse(Map.of("status", "bad"))
                .rawResponse(Map.of("status", "ok"))
                .problemDetail(problemDetail)
                .correlationId("corr-42")
                .requestId("req-42")
                .responseHeaders(responseHeaders)
                .build();

        when(stringRedisTemplate.hasKey(any())).thenReturn(false);
        when(responseValidator.validate("order-service", "payment-service", "/api/payments/process",
                request.getTransformedResponse()))
                .thenReturn(Optional.of(new ResponseMismatch(
                        ResponseMismatch.Kind.MISSING_FIELD,
                        "missing amount",
                        Set.of("amount"),
                        Map.of(),
                        Map.of(),
                        Map.of())));

        UUID failureId = UUID.randomUUID();
        when(failureIngestionService.recordResponseMismatch(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ApiFailure.builder().id(failureId).build());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        var outcome = responseValidationService.validate(request);

        assertThat(outcome.status()).isEqualTo("mismatch");
        assertThat(outcome.failureId()).isEqualTo(failureId);

        org.mockito.ArgumentCaptor<Map> pdCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.ArgumentCaptor<String> corrCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> reqCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Map> hdrCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);

        verify(failureIngestionService).recordResponseMismatch(
                any(), any(), any(), any(),
                pdCaptor.capture(), corrCaptor.capture(), reqCaptor.capture(), hdrCaptor.capture());

        assertThat(pdCaptor.getValue()).containsEntry("detail", "missing amount");
        assertThat(pdCaptor.getValue()).containsEntry("json_path", "/amount");
        assertThat(corrCaptor.getValue()).isEqualTo("corr-42");
        assertThat(reqCaptor.getValue()).isEqualTo("req-42");
        assertThat(hdrCaptor.getValue()).containsEntry("Content-Type", "application/problem+json");
    }
}
