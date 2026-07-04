package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.ApiKey;
import com.selfhealing.gateway.repository.ApiKeyRepository;
import com.selfhealing.gateway.security.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ops/admin endpoints to issue, list and revoke per-tenant API keys (edge/machine
 * credentials). Mounted under {@code /api/internal/**}, so it is guarded by the
 * shared internal-key interceptor (see {@code InternalApiWebConfig}) — this is an
 * operator surface, not a tenant-self-serve one. The raw secret is returned ONCE at
 * issuance; only its hash is persisted.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/admin/api-keys")
@RequiredArgsConstructor
public class AdminApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;

    public record IssueRequest(String tenantId, String name, Integer expiresInDays) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> issue(@RequestBody IssueRequest req) {
        if (req == null || req.tenantId() == null || req.tenantId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(req.tenantId().trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId must be a UUID"));
        }
        LocalDateTime expiresAt = req.expiresInDays() != null && req.expiresInDays() > 0
                ? LocalDateTime.now().plusDays(req.expiresInDays()) : null;

        ApiKeyService.IssuedKey issued = apiKeyService.issue(tenantId, req.name(), null, expiresAt);
        log.info("Issued API key {} for tenant {}", issued.stored().getKeyPrefix(), tenantId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", issued.stored().getId());
        resp.put("tenantId", tenantId);
        resp.put("keyPrefix", issued.stored().getKeyPrefix());
        // Shown ONCE — the operator must copy it now; only the hash is stored.
        resp.put("apiKey", issued.plaintext());
        resp.put("expiresAt", issued.stored().getExpiresAt());
        return ResponseEntity.ok(resp);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam("tenantId") String tenantId) {
        UUID id;
        try {
            id = UUID.fromString(tenantId.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        List<Map<String, Object>> keys = apiKeyRepository.findByTenantId(id).stream()
                .map(AdminApiKeyController::redact)
                .toList();
        return ResponseEntity.ok(keys);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable UUID id) {
        return apiKeyRepository.findById(id).map(key -> {
            key.setRevokedAt(LocalDateTime.now());
            apiKeyRepository.save(key);
            log.info("Revoked API key {} (tenant {})", key.getKeyPrefix(), key.getTenantId());
            return ResponseEntity.ok(Map.<String, Object>of("revoked", true, "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Metadata only — never the hash or secret. */
    private static Map<String, Object> redact(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("keyPrefix", k.getKeyPrefix());
        m.put("createdAt", k.getCreatedAt());
        m.put("lastUsedAt", k.getLastUsedAt());
        m.put("expiresAt", k.getExpiresAt());
        m.put("revokedAt", k.getRevokedAt());
        return m;
    }
}
