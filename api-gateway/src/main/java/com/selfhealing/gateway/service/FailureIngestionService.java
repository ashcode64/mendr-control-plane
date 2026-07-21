package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayInternalProperties;
import com.selfhealing.gateway.dto.ApiFailureEvent;
import com.selfhealing.gateway.dto.IngestFailureRequest;
import com.selfhealing.gateway.dto.ProblemDetail;
import com.selfhealing.gateway.dto.ProxyRequest;
import com.selfhealing.gateway.model.ApiFailure;
import com.selfhealing.gateway.repository.ApiFailureRepository;
import com.selfhealing.gateway.util.ResponseMismatchAnalyzer.ResponseMismatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared failure persistence + Kafka publication for the Java proxy and OpenResty internal APIs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailureIngestionService {

    private static final String FAILURES_TOPIC = "api.failures";
    private static final String FAILURE_DEDUP_KEY_PREFIX = "mendr:fail-dedup:";

    private final ApiFailureRepository failureRepository;
    private final KafkaTemplate<String, ApiFailureEvent> kafkaTemplate;
    private final ServiceRegistryService registry;
    private final DynamicRoutingService routingService;
    private final DnsProbeService dnsProbeService;
    private final StringRedisTemplate stringRedisTemplate;
    private final GatewayInternalProperties internalProperties;

    public IngestOutcome ingest(IngestFailureRequest request) {
        String dedupKey = failureDedupKey(request);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(dedupKey))) {
            log.debug("Failure ingest deduplicated for {}", routeLabel(request));
            return IngestOutcome.deduplicated();
        }

        ProxyRequest proxy = toProxyRequest(request);
        String category = normalizeCategory(request.getFailureCategory());

        String attemptedUrl = request.getAttemptedUrl();
        String registeredBase = request.getRegisteredBaseUrl();
        String targetServiceUrl = request.getTargetServiceUrl();
        String discoveredUrl = request.getDnsProbeDiscoveryUrl();
        String origin = request.getRequestOrigin();

        if ("ROUTING".equals(category)) {
            RoutingEnrichment enrichment = enrichRoutingFailure(
                    request.getTargetService(), attemptedUrl, registeredBase, discoveredUrl);
            registeredBase = enrichment.registeredBaseUrl();
            discoveredUrl = enrichment.dnsProbeDiscoveryUrl();
            if (isBlank(targetServiceUrl)) {
                targetServiceUrl = attemptedUrl;
            }
        } else if (isBlank(registeredBase)) {
            registeredBase = registry.loadRegisteredBaseUrl(request.getTargetService()).orElse(null);
        }

        if (isBlank(targetServiceUrl)) {
            targetServiceUrl = attemptedUrl;
        }

        Map<String, Object> problemDetail = normalizeProblemDetail(request.getProblemDetail());
        ApiFailure failure = persistFromIngest(request, proxy, problemDetail);
        publishEvent(failure, category, attemptedUrl, origin, targetServiceUrl, registeredBase,
                discoveredUrl, request.getCorsBlockedAt(), request.getUpstreamOriginSent(),
                request.getCorrelationId(), request.getRequestId(),
                problemDetail, request.getResponseHeaders());

        int ttl = internalProperties.getFailureDedupTtlSeconds();
        if (ttl > 0) {
            stringRedisTemplate.opsForValue().set(dedupKey, "1", Duration.ofSeconds(ttl));
        }

        return IngestOutcome.accepted(failure.getId());
    }

    public ApiFailure recordRoutingFailure(
            ProxyRequest req, String attemptedUrl, String registeredBase, String message) {
        log.warn("ROUTING FAILURE: {} → {} at {} — {}",
                req.getSourceService(), req.getTargetService(), attemptedUrl, message);

        RoutingEnrichment enrichment = enrichRoutingFailure(
                req.getTargetService(), attemptedUrl, registeredBase, null);

        log.debug("Routing failure enrichment: attemptedUrl={}, cachedRegistered={}, liveRegistered={}, discovered={}",
                attemptedUrl, registeredBase, enrichment.registeredBaseUrl(), enrichment.dnsProbeDiscoveryUrl());

        ApiFailure failure = persistFailure(req, 503, "ROUTING_FAILURE", message, null);
        publishEvent(failure, "ROUTING", attemptedUrl, null, attemptedUrl,
                enrichment.registeredBaseUrl(), enrichment.dnsProbeDiscoveryUrl(),
                null, null, null, null, null, null);
        return failure;
    }

    public ApiFailure recordCorsFailure(ProxyRequest req, String origin, int code, String message) {
        log.warn("CORS FAILURE: origin '{}' blocked → {} — {}", origin, req.getTargetService(), message);
        ApiFailure failure = persistFailure(req, code, "CORS_FAILURE", message, null);
        publishEvent(failure, "CORS", null, origin, null, null, null, "EDGE", null, null, null, null, null);
        return failure;
    }

    public ApiFailure recordResponseMismatch(
            ProxyRequest req,
            Map<String, Object> rawBody,
            Map<String, Object> transformedBody,
            ResponseMismatch mismatch) {
        return recordResponseMismatch(req, rawBody, transformedBody, mismatch, null, null, null, null);
    }

    public ApiFailure recordResponseMismatch(
            ProxyRequest req,
            Map<String, Object> rawBody,
            Map<String, Object> transformedBody,
            ResponseMismatch mismatch,
            Map<String, Object> problemDetail,
            String correlationId,
            String requestId,
            Map<String, Object> responseHeaders) {

        String message = "Response contract mismatch: " + mismatch.summary();
        log.warn("RESPONSE MISMATCH: {} → {}{} — {}",
                req.getSourceService(), req.getTargetService(), req.getEndpoint(), message);

        Map<String, Object> respPayload = new HashMap<>();
        respPayload.put("raw", rawBody);
        respPayload.put("transformed", transformedBody);
        respPayload.put("missingFields", mismatch.missingFields());
        respPayload.put("renameMappings", mismatch.renameMappings());
        respPayload.put("typeCoercions", mismatch.typeCoercions());
        Map<String, Object> normalizedPd = normalizeProblemDetail(problemDetail);
        if (normalizedPd != null && !normalizedPd.isEmpty()) {
            respPayload.put("problemDetail", normalizedPd);
        }

        ApiFailure failure = persistFailureWithResponse(req, 502, "RESPONSE_MISMATCH", message, respPayload);
        publishEvent(failure, "RESPONSE_MISMATCH", null, null, null, null, null, null, null,
                correlationId, requestId, normalizedPd, responseHeaders);
        return failure;
    }

    public ApiFailure recordGenericFailure(
            ProxyRequest req, String url, String registeredBase, int code, String type,
            String message, String responseBody, String category) {
        log.warn("FAILURE [{}]: {}->{}{} → {} {}",
                category, req.getSourceService(), req.getTargetService(), req.getEndpoint(), code, message);
        ApiFailure failure = persistFailure(req, code, type, message, responseBody);
        publishEvent(failure, category, url, null, url, registeredBase, null, null, null, null, null, null, null);
        return failure;
    }

    private RoutingEnrichment enrichRoutingFailure(
            String targetService, String attemptedUrl, String cachedRegistered, String providedDiscovery) {

        String liveRegistered = registry.loadRegisteredBaseUrl(targetService).orElse(null);
        String effectiveRegistered = !isBlank(cachedRegistered) ? cachedRegistered : liveRegistered;

        String discoveredUrl = providedDiscovery;
        if (isBlank(discoveredUrl)) {
            String probeSource = !isBlank(attemptedUrl)
                    ? attemptedUrl
                    : routingService.resolveUrl(targetService);
            discoveredUrl = dnsProbeService.discoverNewUrl(targetService, probeSource)
                    .map(url -> {
                        log.info("DNS probe found candidate for '{}': {}", targetService, url);
                        return url;
                    })
                    .orElse(null);
        }
        return new RoutingEnrichment(effectiveRegistered, discoveredUrl);
    }

    private ApiFailure persistFromIngest(IngestFailureRequest request, ProxyRequest proxy,
                                         Map<String, Object> problemDetail) {
        // Dual-accept: prefer RFC 9457 detail when present, fall back to legacy errorMessage.
        String message = null;
        if (problemDetail != null) {
            Object detail = problemDetail.get("detail");
            if (detail != null && !detail.toString().isBlank()) {
                message = detail.toString();
            }
        }
        if (message == null || message.isBlank()) {
            message = request.getErrorMessage();
        }
        if (request.getResponsePayload() != null && !request.getResponsePayload().isEmpty()) {
            return persistFailureWithResponse(
                    proxy, request.getErrorCode(), request.getErrorType(),
                    message, request.getResponsePayload());
        }
        return persistFailure(proxy, request.getErrorCode(), request.getErrorType(),
                message, null);
    }

    /** Normalize via {@link ProblemDetail} so extensions round-trip consistently on Kafka. */
    static Map<String, Object> normalizeProblemDetail(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return raw;
        ProblemDetail pd = ProblemDetail.fromMap(raw);
        return pd == null ? raw : pd.toMap();
    }

    private ApiFailure persistFailure(ProxyRequest req, int code, String type,
                                      String message, String responseBody) {
        Map<String, Object> respPayload = null;
        if (responseBody != null && !responseBody.isBlank()) {
            respPayload = Map.of("raw", responseBody);
        }
        return failureRepository.save(ApiFailure.builder()
                .serviceA(req.getSourceService()).serviceB(req.getTargetService())
                .endpoint(req.getEndpoint()).httpMethod(req.getMethod())
                .errorCode(code).errorType(type)
                .requestPayload(req.getPayload()).responsePayload(respPayload)
                .errorMessage(message)
                .detectedAt(LocalDateTime.now()).status(ApiFailure.FailureStatus.OPEN)
                .build());
    }

    private ApiFailure persistFailureWithResponse(ProxyRequest req, int code, String type,
                                                  String message, Map<String, Object> responsePayload) {
        return failureRepository.save(ApiFailure.builder()
                .serviceA(req.getSourceService()).serviceB(req.getTargetService())
                .endpoint(req.getEndpoint()).httpMethod(req.getMethod())
                .errorCode(code).errorType(type)
                .requestPayload(req.getPayload()).responsePayload(responsePayload)
                .errorMessage(message)
                .detectedAt(LocalDateTime.now()).status(ApiFailure.FailureStatus.OPEN)
                .build());
    }

    private void publishEvent(ApiFailure failure, String category, String attemptedUrl, String origin,
                                String targetServiceUrl, String registeredBaseUrl, String dnsProbeDiscoveryUrl,
                                String corsBlockedAt, String upstreamOriginSent,
                                String correlationId, String requestId,
                                Map<String, Object> problemDetail, Map<String, Object> responseHeaders) {
        kafkaTemplate.send(FAILURES_TOPIC, failure.getId().toString(), ApiFailureEvent.builder()
                .failureId(failure.getId()).serviceA(failure.getServiceA()).serviceB(failure.getServiceB())
                .endpoint(failure.getEndpoint()).httpMethod(failure.getHttpMethod())
                .errorCode(failure.getErrorCode()).errorType(failure.getErrorType())
                .requestPayload(failure.getRequestPayload()).responsePayload(failure.getResponsePayload())
                .errorMessage(failure.getErrorMessage()).timestamp(failure.getDetectedAt())
                .failureCategory(category).attemptedUrl(attemptedUrl).requestOrigin(origin)
                .targetServiceUrl(targetServiceUrl).registeredBaseUrl(registeredBaseUrl)
                .dnsProbeDiscoveryUrl(dnsProbeDiscoveryUrl)
                .corsBlockedAt(corsBlockedAt)
                .upstreamOriginSent(upstreamOriginSent)
                .correlationId(correlationId)
                .requestId(requestId)
                .problemDetail(problemDetail)
                .responseHeaders(responseHeaders)
                .build());
        log.info("Published [{}] failure event {}", category, failure.getId());
    }

    static String failureDedupKey(IngestFailureRequest request) {
        return com.selfhealing.gateway.tenant.TenantKeys.scoped(
                FAILURE_DEDUP_KEY_PREFIX + request.getSourceService() + ":"
                + request.getTargetService() + ":" + request.getEndpoint());
    }

    private static String routeLabel(IngestFailureRequest request) {
        return request.getSourceService() + "->" + request.getTargetService() + request.getEndpoint();
    }

    private static ProxyRequest toProxyRequest(IngestFailureRequest request) {
        return ProxyRequest.builder()
                .sourceService(request.getSourceService())
                .targetService(request.getTargetService())
                .endpoint(request.getEndpoint())
                .method(request.getHttpMethod() != null ? request.getHttpMethod() : "GET")
                .payload(request.getRequestPayload())
                .build();
    }

    static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "UNKNOWN";
        }
        return category.toUpperCase();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record RoutingEnrichment(String registeredBaseUrl, String dnsProbeDiscoveryUrl) {}

    public record IngestOutcome(String status, UUID failureId) {
        public static IngestOutcome accepted(UUID failureId) {
            return new IngestOutcome("accepted", failureId);
        }

        public static IngestOutcome deduplicated() {
            return new IngestOutcome("deduplicated", null);
        }

        public boolean isDeduplicated() {
            return "deduplicated".equals(status);
        }
    }
}
