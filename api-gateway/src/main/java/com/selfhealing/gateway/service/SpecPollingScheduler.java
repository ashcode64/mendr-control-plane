package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.OpenApiSpecRegistry;
import com.selfhealing.gateway.repository.OpenApiSpecRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * ETag-based OpenAPI spec polling. Complements GitOps push: for services
 * registered with a {@code specUrl}, conditionally GETs and re-imports on change.
 * Fetches go through {@link OpenApiFetchGuard} (registered-base allowlist + size/timeout).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecPollingScheduler {

    private final OpenApiSpecRegistryRepository specRegistryRepository;
    private final OpenApiImportService openApiImportService;
    private final OpenApiFetchGuard fetchGuard;

    @Scheduled(fixedDelayString = "${mendr.openapi.poll-interval-ms:300000}")
    public void poll() {
        List<OpenApiSpecRegistry> specs = specRegistryRepository.findAll().stream()
                .filter(OpenApiSpecRegistry::isActive)
                .filter(s -> s.getSpecUrl() != null && !s.getSpecUrl().isBlank())
                .toList();
        for (OpenApiSpecRegistry spec : specs) {
            try {
                pollOne(spec);
            } catch (Exception e) {
                log.warn("OpenAPI poll failed for {}: {}", spec.getSourceApp(), e.getMessage());
            }
        }
    }

    private void pollOne(OpenApiSpecRegistry spec) throws Exception {
        fetchGuard.assertAllowed(spec.getSpecUrl());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(OpenApiFetchGuard.CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(spec.getSpecUrl()))
                .timeout(Duration.ofMillis(OpenApiFetchGuard.READ_TIMEOUT_MS))
                .GET()
                .header("Accept", "application/json, application/yaml, text/yaml, */*");
        if (spec.getEtag() != null && !spec.getEtag().isBlank()) {
            rb.header("If-None-Match", spec.getEtag());
        }
        HttpResponse<byte[]> res = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() == 304) {
            return;
        }
        if (res.statusCode() < 200 || res.statusCode() >= 300 || res.body() == null) {
            log.warn("OpenAPI poll non-success for {}: {}", spec.getSourceApp(), res.statusCode());
            return;
        }
        if (res.body().length > OpenApiFetchGuard.MAX_SPEC_BYTES) {
            log.warn("OpenAPI poll body too large for {}", spec.getSourceApp());
            return;
        }
        if (res.uri() != null && !res.uri().toString().equals(spec.getSpecUrl())) {
            fetchGuard.assertAllowed(res.uri().toString());
        }
        String etag = res.headers().firstValue("ETag").orElse(null);
        String body = new String(res.body(), StandardCharsets.UTF_8);
        var result = openApiImportService.importSpec(body);
        if (result.isSuccess() && etag != null) {
            spec.setEtag(etag);
            specRegistryRepository.save(spec);
        }
        log.info("OpenAPI poll re-import for {}: success={} hash={}",
                spec.getSourceApp(), result.isSuccess(), result.getSpecHash());
    }
}
