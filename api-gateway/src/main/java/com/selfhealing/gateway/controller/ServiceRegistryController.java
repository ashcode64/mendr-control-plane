package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.dto.manifest.ManifestImportResult;
import com.selfhealing.gateway.dto.manifest.ManifestValidationException;
import com.selfhealing.gateway.dto.openapi.OpenApiImportResult;
import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.service.IngressApiKeyService;
import com.selfhealing.gateway.service.IngressHostIdentityService;
import com.selfhealing.gateway.service.ManifestImportService;
import com.selfhealing.gateway.service.OpenApiImportService;
import com.selfhealing.gateway.service.RouteChangedPublisher;
import com.selfhealing.gateway.service.RouteConfigSnapshotPublisher;
import com.selfhealing.gateway.service.ServiceRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ServiceRegistryController {

    private final ServiceRegistryService    registryService;
    private final ServiceContractRepository contractRepository;
    private final RouteChangedPublisher     routeChangedPublisher;
    private final ManifestImportService     manifestImportService;
    private final OpenApiImportService      openApiImportService;
    private final IngressApiKeyService      ingressApiKeyService;
    private final IngressHostIdentityService ingressHostIdentityService;
    private final RouteConfigSnapshotPublisher snapshotPublisher;

    // ── Service CRUD ──────────────────────────────────────────────────────────

    /** Register or update a service */
    @PostMapping
    public ResponseEntity<ServiceRegistration> register(@RequestBody ServiceRegistration reg) {
        return ResponseEntity.ok(registryService.register(reg));
    }

    /** List all registered services */
    @GetMapping
    public ResponseEntity<List<ServiceRegistration>> list() {
        return ResponseEntity.ok(registryService.getAllServices());
    }

    /** Get a specific service by name */
    @GetMapping("/{name}")
    public ResponseEntity<ServiceRegistration> get(@PathVariable String name) {
        return registryService.getService(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Update a service by ID */
    @PutMapping("/{id}")
    public ResponseEntity<ServiceRegistration> update(
            @PathVariable UUID id, @RequestBody ServiceRegistration reg) {
        reg.setId(id);
        return ResponseEntity.ok(registryService.register(reg));
    }

    /** Deactivate (soft-delete) a service */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deactivate(@PathVariable String name) {
        registryService.deactivate(name);
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Service '" + name + "' deactivated");
        return ResponseEntity.ok(resp);
    }

    /** Trigger immediate health check for one service */
    @PostMapping("/{name}/health-check")
    public ResponseEntity<Map<String, Object>> healthCheck(@PathVariable String name) {
        registryService.healthCheckAll(); // triggers all; could be targeted in a future iteration
        return registryService.getService(name).map(s -> {
            Map<String, Object> resp = new HashMap<>();
            resp.put("service", name);
            resp.put("status", s.getLastHealthStatus());
            resp.put("checkedAt", s.getLastHealthCheck());
            return ResponseEntity.ok(resp);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Manifest import ───────────────────────────────────────────────────────

    /**
     * Onboard a service from a single Mendr manifest (YAML or JSON).
     *
     * <p>Accepts either a multipart file upload (form field {@code file}) or a raw
     * YAML/JSON request body. Registers the service, persists request/response
     * example payloads as contracts, creates explicit outbound route declarations,
     * and triggers a route snapshot republish to the data plane.
     */
    @PostMapping(value = "/import-manifest", consumes = {"multipart/form-data"})
    public ResponseEntity<ManifestImportResult> importManifestMultipart(
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ManifestImportResult.builder()
                    .success(false)
                    .errors(List.of("No manifest file uploaded"))
                    .build());
        }
        try {
            String raw = new String(file.getBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok(manifestImportService.importManifest(raw));
        } catch (ManifestValidationException e) {
            return ResponseEntity.badRequest().body(ManifestImportResult.builder()
                    .success(false)
                    .errors(e.getErrors())
                    .build());
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(ManifestImportResult.builder()
                    .success(false)
                    .errors(List.of("Could not read uploaded file: " + e.getMessage()))
                    .build());
        }
    }

    /** Raw-body variant (text/yaml, application/x-yaml, or application/json). */
    @PostMapping(value = "/import-manifest", consumes = {
            "text/yaml", "application/x-yaml", "application/yaml", "text/plain", "application/json"})
    public ResponseEntity<ManifestImportResult> importManifestRaw(@RequestBody String body) {
        try {
            return ResponseEntity.ok(manifestImportService.importManifest(body));
        } catch (ManifestValidationException e) {
            return ResponseEntity.badRequest().body(ManifestImportResult.builder()
                    .success(false)
                    .errors(e.getErrors())
                    .build());
        }
    }

    // ── OpenAPI import (second ingestion path) ────────────────────────────────

    /**
     * Import an OpenAPI 3.x document (raw YAML/JSON body). Registers the service,
     * creates TEMPLATE/EXACT routes + OPENAPI_DECLARED contracts, soft-prunes
     * removed endpoints, and republishes route snapshots.
     */
    @PostMapping(value = "/import-openapi", consumes = {
            "text/yaml", "application/x-yaml", "application/yaml", "text/plain",
            "application/json", "application/vnd.oai.openapi", "application/vnd.oai.openapi+json"})
    public ResponseEntity<OpenApiImportResult> importOpenApiRaw(@RequestBody String body) {
        OpenApiImportResult result = openApiImportService.importSpec(body);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping(value = "/import-openapi", consumes = {"multipart/form-data"})
    public ResponseEntity<OpenApiImportResult> importOpenApiMultipart(
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(OpenApiImportResult.builder()
                    .success(false)
                    .errors(List.of("No OpenAPI file uploaded"))
                    .build());
        }
        try {
            String raw = new String(file.getBytes(), StandardCharsets.UTF_8);
            OpenApiImportResult result = openApiImportService.importSpec(raw);
            return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(OpenApiImportResult.builder()
                    .success(false)
                    .errors(List.of("Could not read uploaded file: " + e.getMessage()))
                    .build());
        }
    }

    /** Fetch + import from a URL (SSRF-guarded). Body: { "url": "https://..." }. */
    @PostMapping("/import-openapi/from-url")
    public ResponseEntity<OpenApiImportResult> importOpenApiFromUrl(@RequestBody Map<String, String> body) {
        String url = body == null ? null : body.get("url");
        try {
            OpenApiImportResult result = openApiImportService.importFromUrl(url);
            return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(OpenApiImportResult.builder()
                    .success(false)
                    .errors(List.of(e.getMessage()))
                    .build());
        }
    }

    /** Dry-run: returns planned diff without writing. */
    @PostMapping(value = "/import-openapi/dry-run", consumes = {
            "text/yaml", "application/x-yaml", "application/yaml", "text/plain", "application/json"})
    public ResponseEntity<OpenApiImportResult> importOpenApiDryRun(@RequestBody String body) {
        OpenApiImportResult result = openApiImportService.dryRun(body);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    /**
     * Issue a new ingress API key ({@code <prefix>.<secret>}, same as ApiKeyService)
     * bound to a source service. Secret hash + metadata synced to the edge as
     * {@code mendr:apikey:{prefix}}; plaintext returned once.
     * Body: { "sourceService": "order-service" }.
     */
    @PostMapping("/ingress-api-keys")
    public ResponseEntity<Map<String, Object>> issueIngressApiKey(@RequestBody Map<String, String> body) {
        try {
            String source = body == null ? null : body.get("sourceService");
            return ResponseEntity.ok(ingressApiKeyService.issue(source));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Bind a hostname to a source service for Phase 6 Host-fallback identity
     * (when {@code X-Mendr-Key} is absent). Synced as {@code mendr:hostident:{host}}.
     * Body: { "host": "api.acme.com", "sourceService": "order-service" }.
     */
    @PostMapping("/ingress-host-identity")
    public ResponseEntity<Map<String, Object>> registerHostIdentity(@RequestBody Map<String, String> body) {
        try {
            String host = body == null ? null : body.get("host");
            String source = body == null ? null : body.get("sourceService");
            Map<String, Object> out = ingressHostIdentityService.register(host, source);
            snapshotPublisher.bumpSyncVersionAndNotify();
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Contract registration ─────────────────────────────────────────────────

    /**
     * Register an example JSON payload as a schema contract.
     *
     * POST /api/services/{name}/contracts
     * Body: { "endpoint": "/api/orders", "direction": "REQUEST",
     *         "httpMethod": "POST", "examplePayload": { ... } }
     */
    @PostMapping("/{name}/contracts")
    public ResponseEntity<ServiceContract> registerContract(
            @PathVariable String name,
            @RequestBody ServiceContract contract) {
        contract.setServiceName(name);
        return ResponseEntity.ok(registryService.registerContract(contract));
    }

    /** Get all contracts for a service */
    @GetMapping("/{name}/contracts")
    public ResponseEntity<List<ServiceContract>> getContracts(@PathVariable String name) {
        return ResponseEntity.ok(contractRepository.findByServiceNameAndIsActiveTrue(name));
    }

    /** Get contracts for a specific endpoint of a service */
    @GetMapping("/{name}/contracts/{endpoint}")
    public ResponseEntity<List<ServiceContract>> getContractsByEndpoint(
            @PathVariable String name,
            @PathVariable String endpoint) {
        return ResponseEntity.ok(
                contractRepository.findByServiceNameAndEndpointAndIsActiveTrue(name, "/" + endpoint));
    }

    /** Delete a contract */
    @DeleteMapping("/contracts/{id}")
    public ResponseEntity<Map<String, Object>> deleteContract(@PathVariable UUID id) {
        contractRepository.findById(id).ifPresent(c -> {
            c.setActive(false);
            contractRepository.save(c);
            routeChangedPublisher.publishAll();
        });
        return ResponseEntity.ok(Map.of("message", "Contract deactivated", "id", id));
    }
}
