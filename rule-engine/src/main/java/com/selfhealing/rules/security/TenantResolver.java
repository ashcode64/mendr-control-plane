package com.selfhealing.rules.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a caller-provided tenant identifier into the internal tenant UUID used by
 * Row-Level Security. The identifier may already be a tenant UUID (gateway-resolved or the
 * default tenant) or a WorkOS organization id, mapped via the {@code tenants} registry
 * (mirrors the gateway's {@code TenantRepository.findByWorkosOrgId}). Cached.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantResolver {

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, UUID> orgIdCache = new ConcurrentHashMap<>();

    /** Resolve a raw value (UUID or WorkOS org id) to a tenant UUID, or null. */
    public UUID resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            // Fall through: treat it as a WorkOS org id.
        }
        return resolveOrgId(value);
    }

    /** Resolve a WorkOS org id to a tenant UUID via the tenants registry (cached). */
    public UUID resolveOrgId(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        UUID cached = orgIdCache.get(orgId);
        if (cached != null) {
            return cached;
        }
        try {
            String id = jdbcTemplate.queryForObject(
                    "SELECT id::text FROM tenants WHERE workos_org_id = ?", String.class, orgId);
            if (id == null) {
                return null;
            }
            UUID resolved = UUID.fromString(id);
            orgIdCache.put(orgId, resolved);
            return resolved;
        } catch (EmptyResultDataAccessException e) {
            log.warn("No tenant mapped for the provided WorkOS org id");
            return null;
        } catch (Exception e) {
            log.warn("Tenant resolution failed: {}", e.getMessage());
            return null;
        }
    }
}
