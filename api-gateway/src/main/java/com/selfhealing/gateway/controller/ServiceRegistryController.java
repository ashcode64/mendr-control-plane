package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.service.RouteChangedPublisher;
import com.selfhealing.gateway.service.ServiceRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ServiceRegistryController {

    private final ServiceRegistryService    registryService;
    private final ServiceContractRepository contractRepository;
    private final RouteChangedPublisher     routeChangedPublisher;

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
