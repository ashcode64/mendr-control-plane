package com.selfhealing.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.dto.manifest.ManifestImportResult;
import com.selfhealing.gateway.service.ManifestImportService;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GitOps / policy-as-code push endpoint — CI posts mendr.yaml and we record a revision hash.
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/gitops")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GitOpsManifestController {

    private final ManifestImportService manifestImportService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @PostMapping("/manifest")
    public ResponseEntity<Map<String, Object>> applyManifest(
            @RequestBody String raw,
            @RequestHeader(value = "X-Git-Commit", required = false) String gitCommit,
            @RequestHeader(value = "X-Git-Repo", required = false) String gitRepo) {
        ManifestImportResult result = manifestImportService.importManifest(raw);
        String hash = sha256(raw);
        UUID tenant = TenantContext.currentOrDefault();
        String revKey = "mendr:gitops:revision:" + tenant + ":" + hash.substring(0, 16);
        Map<String, Object> rev = new LinkedHashMap<>();
        rev.put("hash", hash);
        rev.put("gitCommit", gitCommit);
        rev.put("gitRepo", gitRepo);
        rev.put("appliedAt", Instant.now().toString());
        rev.put("tenantId", tenant.toString());
        rev.put("serviceName", result.getService());
        try {
            stringRedisTemplate.opsForValue().set(revKey, objectMapper.writeValueAsString(rev));
            stringRedisTemplate.opsForValue().set("mendr:gitops:latest:" + tenant, revKey);
        } catch (Exception e) {
            log.warn("Failed to persist gitops revision: {}", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceName", result.getService());
        out.put("contractsCreated", result.getContractsCreated());
        out.put("routesCreated", result.getRoutesCreated());
        out.put("warnings", result.getWarnings());
        out.put("revisionHash", hash);
        out.put("gitCommit", gitCommit);
        out.put("status", result.isSuccess() ? "ok" : "error");
        return ResponseEntity.ok(out);
    }

    @GetMapping("/revisions/latest")
    public ResponseEntity<Map<String, Object>> latestRevision() {
        UUID tenant = TenantContext.currentOrDefault();
        try {
            String revKey = stringRedisTemplate.opsForValue().get("mendr:gitops:latest:" + tenant);
            if (revKey == null) {
                return ResponseEntity.notFound().build();
            }
            String json = stringRedisTemplate.opsForValue().get(revKey);
            if (json == null) {
                return ResponseEntity.notFound().build();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }
}
