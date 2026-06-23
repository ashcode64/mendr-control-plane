package com.selfhealing.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.config.GatewayFastPathProperties;
import com.selfhealing.gateway.dto.ProxyRequest;
import com.selfhealing.gateway.model.RouteConfig;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.transform.StreamingProxyClient;
import com.selfhealing.gateway.util.ProxyEnvelopeParser;
import com.selfhealing.gateway.util.ProxyEnvelopeParser.ParsedEnvelope;
import com.selfhealing.gateway.util.ResponseMismatchAnalyzer.ResponseMismatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Five-layer proxy pipeline with an L1-cached fast path when no rules/CORS/contracts apply.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayProxyService {

    private final TransformationEngine requestEngine;
    private final ResponseTransformationEngine responseEngine;
    private final ServiceRegistryService registry;
    private final DynamicRoutingService routingService;
    private final DynamicCorsService corsService;
    private final ResponseContractValidator responseValidator;
    private final FailureIngestionService failureIngestionService;
    private final RestTemplate restTemplate;
    private final RouteConfigService routeConfigService;
    private final ProxyEnvelopeParser envelopeParser;
    private final ObjectMapper objectMapper;
    private final StreamingProxyClient streamingProxyClient;
    private final GatewayFastPathProperties fastPathProperties;

    private static final List<String> PASSTHROUGH_HEADERS = List.of(
            "Authorization", "X-Api-Key", "X-Correlation-Id", "X-Request-Id",
            "X-Trace-Id", "X-B3-TraceId", "X-B3-SpanId", "X-B3-ParentSpanId",
            "X-B3-Sampled", "X-Tenant-Id", "X-User-Id", "X-Forwarded-For",
            "traceparent", "tracestate", "Origin"
    );

    public ResponseEntity<Map<String, Object>> proxy(byte[] rawBody) {
        ParsedEnvelope envelope = envelopeParser.parse(rawBody);
        ProxyRequest request = envelope.request();
        RouteConfig cfg = routeConfigService.get(
                request.getSourceService(), request.getTargetService(), request.getEndpoint());

        if (cfg.fastPathEligible()) {
            log.debug("Fast path: {} → {}{}", request.getSourceService(),
                    request.getTargetService(), request.getEndpoint());
            return executeFastPath(request, envelope.payloadBytes(), cfg);
        }
        if (fastPathProperties.isStreamingTransformsEnabled() && cfg.streamPathEligible()) {
            log.debug("Streaming path: {} → {}{}", request.getSourceService(),
                    request.getTargetService(), request.getEndpoint());
            return executeStreamingPath(request, envelope.payloadBytes(), cfg);
        }
        return executeSlowPath(request, cfg);
    }

    /** Backward-compatible entry for callers that already have a parsed envelope. */
    public ResponseEntity<Map<String, Object>> proxy(ProxyRequest request) {
        RouteConfig cfg = routeConfigService.get(
                request.getSourceService(), request.getTargetService(), request.getEndpoint());
        if (cfg.fastPathEligible()) {
            byte[] payloadBytes = serializePayload(request.getPayload());
            return executeFastPath(request, payloadBytes, cfg);
        }
        if (fastPathProperties.isStreamingTransformsEnabled() && cfg.streamPathEligible()) {
            byte[] payloadBytes = serializePayload(request.getPayload());
            return executeStreamingPath(request, payloadBytes, cfg);
        }
        return executeSlowPath(request, cfg);
    }

    private ResponseEntity<Map<String, Object>> executeFastPath(
            ProxyRequest request, byte[] payloadBytes, RouteConfig cfg) {

        String targetUrl = cfg.getTargetBaseUrl() + request.getEndpoint();
        HttpHeaders headers = buildOutboundHeaders(request, cfg);

        try {
            HttpEntity<byte[]> entity = new HttpEntity<>(payloadBytes, headers);
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    targetUrl,
                    HttpMethod.valueOf(request.getMethod()),
                    entity,
                    byte[].class);

            byte[] body = resp.getBody() != null ? resp.getBody() : new byte[0];
            HttpHeaders respHeaders = new HttpHeaders();
            respHeaders.setContentType(MediaType.APPLICATION_JSON);
            resp.getHeaders().forEach((name, values) -> {
                if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                    respHeaders.put(name, values);
                }
            });

            Map<String, Object> responseBody = parseResponseBody(body);
            return ResponseEntity.status(resp.getStatusCode()).headers(respHeaders).body(responseBody);

        } catch (ResourceAccessException connEx) {
            return handleRoutingFailure(request, targetUrl, cfg.getRegisteredBaseUrl(), connEx.getMessage());

        } catch (HttpClientErrorException ex) {
            return handleGenericFailure(request, targetUrl, cfg.getRegisteredBaseUrl(),
                    ex.getStatusCode().value(), httpStatusName(ex.getStatusCode()),
                    ex.getMessage(), ex.getResponseBodyAsString(), "SCHEMA_MISMATCH");

        } catch (HttpServerErrorException ex) {
            return handleGenericFailure(request, targetUrl, cfg.getRegisteredBaseUrl(),
                    ex.getStatusCode().value(), httpStatusName(ex.getStatusCode()),
                    ex.getMessage(), ex.getResponseBodyAsString(), "SCHEMA_MISMATCH");

        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (DynamicCorsService.isRoutingFailure(0, msg)) {
                return handleRoutingFailure(request, targetUrl, cfg.getRegisteredBaseUrl(), msg);
            }
            return handleGenericFailure(request, targetUrl, cfg.getRegisteredBaseUrl(),
                    500, "INTERNAL_ERROR", msg, null, "UNKNOWN");
        }
    }

    private ResponseEntity<Map<String, Object>> executeStreamingPath(
            ProxyRequest request, byte[] payloadBytes, RouteConfig cfg) {

        String targetUrl = cfg.getTargetBaseUrl() + request.getEndpoint();

        String origin = request.getHeaders() != null ? request.getHeaders().get("Origin") : null;
        if (origin != null && !origin.isBlank()
                && cfg.isCorsActive()
                && !cfg.isOriginAllowed(origin)) {
            return handleCorsFailure(request, origin, 403,
                    "CORS policy blocked origin '" + origin + "' for '" + request.getTargetService() + "'");
        }

        HttpHeaders headers = buildOutboundHeaders(request, cfg);
        Map<String, String> headerMap = new HashMap<>();
        headers.forEach((k, v) -> {
            if (!v.isEmpty()) {
                headerMap.put(k, v.get(0));
            }
        });

        try {
            StreamingProxyClient.StreamResult result = streamingProxyClient.forward(
                    request.getMethod(), targetUrl, payloadBytes, headerMap,
                    cfg.getRequestProgram(), cfg.getResponseProgram());

            if (result.status() >= 400) {
                return handleGenericFailure(request, targetUrl, cfg.getRegisteredBaseUrl(),
                        result.status(), "HTTP_" + result.status(),
                        "Downstream returned " + result.status(),
                        new String(result.body()), "SCHEMA_MISMATCH");
            }

            HttpHeaders respHeaders = new HttpHeaders();
            respHeaders.setContentType(MediaType.APPLICATION_JSON);
            if (origin != null && cfg.isOriginAllowed(origin)) {
                corsService.applyCorsHeaders(respHeaders, request.getTargetService(), origin);
            }
            Map<String, Object> body = parseResponseBody(result.body());
            return ResponseEntity.status(result.status()).headers(respHeaders).body(body);

        } catch (java.net.ConnectException | java.net.UnknownHostException connEx) {
            return handleRoutingFailure(request, targetUrl, cfg.getRegisteredBaseUrl(), connEx.getMessage());
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (DynamicCorsService.isRoutingFailure(0, msg)) {
                return handleRoutingFailure(request, targetUrl, cfg.getRegisteredBaseUrl(), msg);
            }
            return handleGenericFailure(request, targetUrl, cfg.getRegisteredBaseUrl(),
                    500, "INTERNAL_ERROR", msg, null, "UNKNOWN");
        }
    }

    private ResponseEntity<Map<String, Object>> executeSlowPath(ProxyRequest request, RouteConfig cfg) {
        String resolvedBase = cfg.getTargetBaseUrl();
        String targetUrl = resolvedBase + request.getEndpoint();
        String registeredBase = cfg.getRegisteredBaseUrl();

        HttpHeaders headers = buildOutboundHeaders(request, cfg);

        Map<String, Object> requestPayload = requestEngine.applyTransformations(
                request.getSourceService(), request.getTargetService(),
                request.getEndpoint(), request.getPayload());

        log.debug("Slow path proxy {} → {} [{}{}]",
                request.getSourceService(), request.getTargetService(),
                request.getMethod(), request.getEndpoint());

        String origin = request.getHeaders() != null ? request.getHeaders().get("Origin") : null;
        if (origin != null && !origin.isBlank()
                && cfg.isCorsActive()
                && !cfg.isOriginAllowed(origin)) {
            return handleCorsFailure(request, origin, 403,
                    "CORS policy blocked origin '" + origin + "' for '" + request.getTargetService() + "'");
        }

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestPayload, headers);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> resp = restTemplate.exchange(
                    targetUrl, HttpMethod.valueOf(request.getMethod()), entity, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> rawBody = resp.getBody() != null ? resp.getBody() : new HashMap<>();

            Map<String, Object> transformedBody = responseEngine.applyResponseTransformations(
                    request.getSourceService(), request.getTargetService(),
                    request.getEndpoint(), rawBody);

            Optional<ResponseMismatch> responseMismatch = responseValidator.validate(
                    request.getSourceService(), request.getTargetService(),
                    request.getEndpoint(), transformedBody);
            if (responseMismatch.isPresent()) {
                return handleResponseMismatchFailure(request, rawBody, transformedBody, responseMismatch.get());
            }

            HttpHeaders respHeaders = new HttpHeaders();
            if (origin != null && cfg.isOriginAllowed(origin)) {
                corsService.applyCorsHeaders(respHeaders, request.getTargetService(), origin);
            }

            return ResponseEntity.status(resp.getStatusCode()).headers(respHeaders).body(transformedBody);

        } catch (ResourceAccessException connEx) {
            return handleRoutingFailure(request, targetUrl, registeredBase, connEx.getMessage());

        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 403 && origin != null) {
                return handleCorsFailure(request, origin, 403, ex.getMessage());
            }
            return handleGenericFailure(request, targetUrl, registeredBase,
                    ex.getStatusCode().value(), httpStatusName(ex.getStatusCode()),
                    ex.getMessage(), ex.getResponseBodyAsString(), "SCHEMA_MISMATCH");

        } catch (HttpServerErrorException ex) {
            return handleGenericFailure(request, targetUrl, registeredBase,
                    ex.getStatusCode().value(), httpStatusName(ex.getStatusCode()),
                    ex.getMessage(), ex.getResponseBodyAsString(), "SCHEMA_MISMATCH");

        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (DynamicCorsService.isCorsFailure(0, msg)) {
                return handleCorsFailure(request, origin, 403, msg);
            }
            if (DynamicCorsService.isRoutingFailure(0, msg)) {
                return handleRoutingFailure(request, targetUrl, registeredBase, msg);
            }
            return handleGenericFailure(request, targetUrl, registeredBase, 500, "INTERNAL_ERROR", msg, null, "UNKNOWN");
        }
    }

    private HttpHeaders buildOutboundHeaders(ProxyRequest request, RouteConfig cfg) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        passthroughHeaders(headers, request.getHeaders());
        applyAuthFromConfig(headers, cfg, request.getHeaders());
        headers.set("X-Mendr-Gateway", "true");
        headers.set("X-Source-Service", request.getSourceService());
        headers.set("X-Resolved-URL", cfg.getTargetBaseUrl());
        return headers;
    }

    private void passthroughHeaders(HttpHeaders outbound, Map<String, String> incoming) {
        if (incoming == null) return;
        incoming.forEach((key, value) -> {
            if (PASSTHROUGH_HEADERS.stream().anyMatch(k -> k.equalsIgnoreCase(key))) {
                outbound.set(key, value);
            }
        });
    }

    private void applyAuthFromConfig(HttpHeaders outbound, RouteConfig cfg, Map<String, String> incoming) {
        if (cfg.getAuthType() == null || cfg.getAuthType() == ServiceRegistration.AuthType.NONE) {
            return;
        }
        switch (cfg.getAuthType()) {
            case JWT_BEARER -> {
                String headerName = cfg.getAuthHeaderName() != null ? cfg.getAuthHeaderName() : "Authorization";
                String existing = incoming != null ? incoming.get(headerName) : null;
                if (existing != null && !existing.isBlank()) {
                    outbound.set(headerName, existing);
                } else {
                    String token = resolveSecret(cfg.getAuthSecretRef());
                    if (token != null) outbound.set(headerName, "Bearer " + token);
                }
            }
            case API_KEY_HEADER -> {
                String headerName = cfg.getAuthHeaderName() != null ? cfg.getAuthHeaderName() : "X-Api-Key";
                String existing = incoming != null ? incoming.get(headerName) : null;
                if (existing != null && !existing.isBlank()) {
                    outbound.set(headerName, existing);
                } else {
                    String key = resolveSecret(cfg.getAuthSecretRef());
                    if (key != null) outbound.set(headerName, key);
                }
            }
            case API_KEY_QUERY -> log.debug("API_KEY_QUERY auth for {} — query param expected in endpoint", cfg.getTargetService());
            case BASIC -> {
                String encoded = resolveSecret(cfg.getAuthSecretRef());
                if (encoded != null) outbound.set("Authorization", "Basic " + encoded);
            }
            default -> { }
        }
    }

    private String resolveSecret(String envVarName) {
        if (envVarName == null || envVarName.isBlank()) return null;
        return System.getenv(envVarName);
    }

    private Map<String, Object> parseResponseBody(byte[] body) {
        if (body.length == 0) return new HashMap<>();
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Fast path response is not JSON object, wrapping as raw string");
            return Map.of("raw", new String(body));
        }
    }

    private byte[] serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return new byte[0];
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize proxy payload", e);
        }
    }

    private static final java.util.Set<String> HOP_BY_HOP = java.util.Set.of(
            "connection", "keep-alive", "transfer-encoding", "te", "trailer",
            "upgrade", "proxy-authorization", "proxy-authenticate");

    // ── Failure handlers ──────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> handleRoutingFailure(
            ProxyRequest req, String attemptedUrl, String registeredBase, String message) {
        var failure = failureIngestionService.recordRoutingFailure(req, attemptedUrl, registeredBase, message);
        return errorBody(failure.getId(), 503, "ROUTING_FAILURE",
                "Service '" + req.getTargetService() + "' unreachable. Self-healing triggered.", message);
    }

    private ResponseEntity<Map<String, Object>> handleCorsFailure(
            ProxyRequest req, String origin, int code, String message) {
        var failure = failureIngestionService.recordCorsFailure(req, origin, code, message);
        return errorBody(failure.getId(), 403, "CORS_FAILURE",
                "CORS blocked origin '" + origin + "'. Self-healing triggered.", message);
    }

    private ResponseEntity<Map<String, Object>> handleResponseMismatchFailure(
            ProxyRequest req,
            Map<String, Object> rawBody,
            Map<String, Object> transformedBody,
            ResponseMismatch mismatch) {
        var failure = failureIngestionService.recordResponseMismatch(req, rawBody, transformedBody, mismatch);
        String message = "Response contract mismatch: " + mismatch.summary();
        return errorBody(failure.getId(), 502, "RESPONSE_MISMATCH",
                "Downstream response does not match caller contract. Self-healing triggered.", message);
    }

    private ResponseEntity<Map<String, Object>> handleGenericFailure(
            ProxyRequest req, String url, String registeredBase, int code, String type,
            String message, String responseBody, String category) {
        var failure = failureIngestionService.recordGenericFailure(
                req, url, registeredBase, code, type, message, responseBody, category);
        return errorBody(failure.getId(), code, type, "Service call failed. Self-healing triggered.", message);
    }

    private static String httpStatusName(HttpStatusCode statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return resolved != null ? resolved.name() : "HTTP_" + statusCode.value();
    }

    private ResponseEntity<Map<String, Object>> errorBody(
            Object id, int status, String type, String message, String detail) {
        return ResponseEntity.status(status).body(Map.of(
                "error", type, "failureId", id, "status", status,
                "message", message, "detail", detail != null ? detail : "",
                "selfHealingTriggered", true));
    }
}
