package com.selfhealing.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Probes candidate URLs to discover where a service has moved.
 * Strategy:
 *  1. Try common port variants of the original host
 *  2. Try common hostname variants (v2, new, service-name-2, etc.)
 *  3. Verify reachability via HTTP health check
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DnsProbeService {

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;

    private static final int PROBE_TIMEOUT_MS = 3000;

    /**
     * Given a failed URL, probe candidate alternatives and return the first reachable one.
     */
    public Optional<String> discoverNewUrl(String serviceName, String failedUrl) {
        List<String> candidates = buildCandidates(serviceName, failedUrl);
        log.info("Probing {} candidate URLs for service '{}' (failed: {})", candidates.size(), serviceName, failedUrl);

        for (String candidate : candidates) {
            ProbeResult result = probe(candidate);
            logProbe(serviceName, candidate, result);

            if (result.reachable()) {
                log.info("✓ Found reachable URL for '{}': {}", serviceName, candidate);
                return Optional.of(candidate);
            }
        }

        log.warn("No reachable URL found for service '{}'", serviceName);
        return Optional.empty();
    }

    /**
     * Check whether a specific URL is reachable.
     */
    public ProbeResult probe(String url) {
        long start = System.currentTimeMillis();
        try {
            // First try DNS resolution
            URI uri = URI.create(url);
            InetAddress.getByName(uri.getHost());

            // Then try HTTP health endpoint
            String healthUrl = url.endsWith("/") ? url + "actuator/health" : url + "/actuator/health";
            restTemplate.getForEntity(healthUrl, String.class);

            long elapsed = System.currentTimeMillis() - start;
            return new ProbeResult(true, 200, (int) elapsed, null);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new ProbeResult(false, null, (int) elapsed, e.getMessage());
        }
    }

    /**
     * Build a list of candidate URLs to probe, given the original failed URL.
     * In production this would also query a service mesh / Consul / k8s API.
     */
    private List<String> buildCandidates(String serviceName, String failedUrl) {
        List<String> candidates = new ArrayList<>();
        try {
            URI uri = URI.create(failedUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            String scheme = uri.getScheme();

            // 1. Same host, alternate ports
            for (int p : new int[]{8091, 8090, 8092, 8093, 8080, 8081, 8082, 8083, 9090, 443, 80}) {
                if (p != port) candidates.add(scheme + "://" + host + ":" + p);
            }

            // 2. Hostname variants (version bumps, blue/green, canary)
            String[] suffixes = {"-v2", "-new", "-2", "-blue", "-green", ".internal", ".local"};
            for (String suffix : suffixes) {
                candidates.add(scheme + "://" + host + suffix + (port > 0 ? ":" + port : ""));
            }

            // 3. Service name variants with common port
            String baseName = serviceName.toLowerCase().replace("_", "-");
            String[] hostVariants = {
                baseName + ":8080", baseName + "-svc:8080",
                baseName + ".default.svc.cluster.local:8080",
                baseName + "-service:8080",
            };
            for (String variant : hostVariants) {
                candidates.add("http://" + variant);
            }

        } catch (Exception e) {
            log.warn("Could not parse failed URL '{}' to build candidates: {}", failedUrl, e.getMessage());
        }
        return candidates;
    }

    private void logProbe(String serviceName, String url, ProbeResult result) {
        try {
            jdbcTemplate.update("""
                INSERT INTO dns_probe_log
                    (service_name, probed_url, http_status, reachable, response_time_ms, error_message)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                serviceName, url, result.httpStatus(), result.reachable(),
                result.responseTimeMs(), result.errorMessage());
        } catch (Exception e) {
            log.debug("Failed to log probe result: {}", e.getMessage());
        }
    }

    public record ProbeResult(boolean reachable, Integer httpStatus, int responseTimeMs, String errorMessage) {}
}
