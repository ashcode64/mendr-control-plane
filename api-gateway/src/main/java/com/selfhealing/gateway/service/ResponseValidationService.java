package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayInternalProperties;
import com.selfhealing.gateway.dto.ProxyRequest;
import com.selfhealing.gateway.dto.ValidateResponseRequest;
import com.selfhealing.gateway.model.ApiFailure;
import com.selfhealing.gateway.util.ResponseMismatchAnalyzer.ResponseMismatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Async response contract validation for the OpenResty data plane (v2.1).
 * First mismatch escalates; subsequent reports within TTL are suppressed (Java safety net).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseValidationService {

    private static final String DEDUP_KEY_PREFIX = "mendr:validate-dedup:";

    private final ResponseContractValidator responseValidator;
    private final FailureIngestionService failureIngestionService;
    private final StringRedisTemplate stringRedisTemplate;
    private final GatewayInternalProperties internalProperties;

    public ValidationOutcome validate(ValidateResponseRequest request) {
        String dedupKey = dedupKey(request);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(dedupKey))) {
            log.debug("Validate-response deduplicated for {}", routeLabel(request));
            return ValidationOutcome.deduplicated();
        }

        Map<String, Object> transformed = request.getTransformedResponse() != null
                ? request.getTransformedResponse()
                : new HashMap<>();

        Optional<ResponseMismatch> mismatch = responseValidator.validate(
                request.getSourceService(),
                request.getTargetService(),
                request.getEndpoint(),
                transformed);

        if (mismatch.isEmpty()) {
            return ValidationOutcome.ok();
        }

        ProxyRequest proxy = ProxyRequest.builder()
                .sourceService(request.getSourceService())
                .targetService(request.getTargetService())
                .endpoint(request.getEndpoint())
                .method(request.getHttpMethod() != null ? request.getHttpMethod() : "GET")
                .payload(request.getRequestPayload())
                .headers(request.getRequestHeaders())
                .build();

        Map<String, Object> rawBody = request.getRawResponse() != null
                ? request.getRawResponse()
                : new HashMap<>();

        ApiFailure failure = failureIngestionService.recordResponseMismatch(
                proxy, rawBody, transformed, mismatch.get());

        int ttl = internalProperties.getValidateDedupTtlSeconds();
        if (ttl > 0) {
            stringRedisTemplate.opsForValue().set(dedupKey, "1", Duration.ofSeconds(ttl));
        }

        return ValidationOutcome.mismatch(failure.getId());
    }

    private static String dedupKey(ValidateResponseRequest request) {
        return com.selfhealing.gateway.tenant.TenantKeys.scoped(
                DEDUP_KEY_PREFIX + request.getSourceService() + ":"
                + request.getTargetService() + ":" + request.getEndpoint());
    }

    private static String routeLabel(ValidateResponseRequest request) {
        return request.getSourceService() + "->" + request.getTargetService() + request.getEndpoint();
    }

    public record ValidationOutcome(String status, java.util.UUID failureId) {
        static ValidationOutcome ok() {
            return new ValidationOutcome("ok", null);
        }

        static ValidationOutcome deduplicated() {
            return new ValidationOutcome("deduplicated", null);
        }

        static ValidationOutcome mismatch(java.util.UUID failureId) {
            return new ValidationOutcome("mismatch", failureId);
        }
    }
}
