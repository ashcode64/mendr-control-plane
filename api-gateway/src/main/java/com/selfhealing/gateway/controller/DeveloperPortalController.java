package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.OpenApiSpecRegistry;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.OpenApiSpecRegistryRepository;
import com.selfhealing.gateway.repository.ServiceRegistrationRepository;
import com.selfhealing.gateway.service.IngressApiKeyService;
import com.selfhealing.gateway.service.UsageMeteringService;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Developer portal / API catalog surface — list published APIs, docs metadata, and self-service keys.
 */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DeveloperPortalController {

    private final ServiceRegistrationRepository serviceRegistrationRepository;
    private final OpenApiSpecRegistryRepository openApiSpecRegistryRepository;
    private final IngressApiKeyService ingressApiKeyService;
    private final JdbcTemplate jdbcTemplate;
    private final UsageMeteringService usageMeteringService;

    @GetMapping("/catalog")
    public ResponseEntity<List<Map<String, Object>>> catalog() {
        List<ServiceRegistration> services = serviceRegistrationRepository.findAll().stream()
                .filter(ServiceRegistration::isActive)
                .toList();
        List<Map<String, Object>> out = services.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("name", s.getName());
            m.put("description", s.getDescription());
            m.put("baseUrl", s.getBaseUrl());
            m.put("protocol", s.getProtocol() != null ? s.getProtocol() : "HTTP");
            m.put("healthEndpoint", s.getHealthEndpoint());
            m.put("teamEmail", s.getTeamEmail());
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/specs")
    public ResponseEntity<List<OpenApiSpecRegistry>> specs() {
        return ResponseEntity.ok(openApiSpecRegistryRepository.findAll());
    }

    @GetMapping("/specs/{sourceApp}")
    public ResponseEntity<?> specByApp(@PathVariable String sourceApp) {
        return openApiSpecRegistryRepository
                .findFirstBySourceAppAndIsActiveTrueOrderByImportedAtDesc(sourceApp)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api-keys")
    public ResponseEntity<?> issueKey(@RequestBody Map<String, String> body) {
        String sourceService = body.get("sourceService");
        if (sourceService == null || sourceService.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceService required"));
        }
        try {
            var issued = ingressApiKeyService.issue(sourceService);
            return ResponseEntity.ok(issued);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> usage() {
        Map<String, Object> out = new HashMap<>(
                usageMeteringService.usageForTenant(TenantContext.currentOrDefault()));
        try {
            Long failures = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM api_failures WHERE detected_at > now() - interval '24 hours'",
                    Long.class);
            out.put("failures24h", failures != null ? failures : 0);
        } catch (Exception e) {
            out.put("failures24h", 0);
        }
        try {
            Long services = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM services WHERE is_active = true", Long.class);
            out.put("activeServices", services != null ? services : 0);
        } catch (Exception e) {
            out.put("activeServices", 0);
        }
        try {
            var row = jdbcTemplate.queryForMap(
                    "SELECT quota_rpm, quota_rpd FROM tenants WHERE id = COALESCE("
                            + "NULLIF(current_setting('app.current_tenant', true), '')::uuid, "
                            + "'00000000-0000-0000-0000-000000000001'::uuid) LIMIT 1");
            out.put("quotaRpm", row.get("quota_rpm"));
            out.put("quotaRpd", row.get("quota_rpd"));
        } catch (Exception e) {
            out.put("quotaRpm", null);
            out.put("quotaRpd", null);
        }
        out.put("quotaRpmHint", "See tenant.quota_rpm for plan limits");
        return ResponseEntity.ok(out);
    }

    /** API versioning / deprecation metadata for portal consumers. */
    @GetMapping("/versions")
    public ResponseEntity<List<Map<String, Object>>> versions() {
        List<Map<String, Object>> out = serviceRegistrationRepository.findAll().stream()
                .filter(ServiceRegistration::isActive)
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", s.getName());
                    m.put("protocol", s.getProtocol() != null ? s.getProtocol() : "HTTP");
                    Map<String, Object> versioning = extractVersioning(s.getRetryPolicyJson());
                    m.put("api_version", versioning.getOrDefault("apiVersion", "v1"));
                    m.put("deprecated", String.valueOf(versioning.getOrDefault("deprecated", "false")));
                    if (versioning.get("sunsetAt") != null) {
                        m.put("sunset_at", versioning.get("sunsetAt"));
                    }
                    return m;
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractVersioning(Map<String, Object> retryPolicyJson) {
        if (retryPolicyJson == null || retryPolicyJson.isEmpty()) {
            return Map.of();
        }
        if (retryPolicyJson.get("versioning") instanceof Map<?, ?> nested) {
            return (Map<String, Object>) nested;
        }
        Map<String, Object> out = new HashMap<>();
        if (retryPolicyJson.get("apiVersion") != null) {
            out.put("apiVersion", retryPolicyJson.get("apiVersion"));
        }
        if (retryPolicyJson.get("deprecated") != null) {
            out.put("deprecated", retryPolicyJson.get("deprecated"));
        }
        if (retryPolicyJson.get("sunsetAt") != null) {
            out.put("sunsetAt", retryPolicyJson.get("sunsetAt"));
        }
        return out;
    }

    /** Monetization / billing hook — edge success-path usage counters for the current tenant. */
    @GetMapping("/billing/usage")
    public ResponseEntity<Map<String, Object>> billingUsage() {
        Map<String, Object> out = new HashMap<>(
                usageMeteringService.billingForTenant(TenantContext.currentOrDefault()));
        try {
            var row = jdbcTemplate.queryForMap(
                    "SELECT quota_metadata FROM tenants WHERE id = COALESCE("
                            + "NULLIF(current_setting('app.current_tenant', true), '')::uuid, "
                            + "'00000000-0000-0000-0000-000000000001'::uuid) LIMIT 1");
            out.put("plan", row.get("quota_metadata"));
        } catch (Exception e) {
            out.put("plan", Map.of());
        }
        return ResponseEntity.ok(out);
    }
}
