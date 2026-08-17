package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Full route-config sync payload for data-plane long-poll consumers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteConfigSyncPayload {

    private long version;
    private Map<String, String> routes;
    private List<String> removed;

    /**
     * Per-host ingress routing tables for transparent HTTP edges advertising
     * the {@code ingress} capability. Shape:
     * {@code { "api.acme.com": [ {path, method, targetService, endpointTemplate, enforce, priority}, ... ] }}
     */
    private Map<String, List<Map<String, Object>>> ingressTables;

    /**
     * Ingress API-key records for edges: map of {@code mendr:apikey:{prefix}} → JSON
     * {@code {keyHash, sourceService, tenantId, expiresAt?, revokedAt?}} where
     * {@code keyHash} is sha256(secret) for keys of the form {@code <prefix>.<secret>}
     * (same as {@code ApiKeyService}). Only shipped to edges advertising {@code ingress}.
     */
    private Map<String, String> apiKeys;

    /**
     * Host → identity fallback for edges (Phase 6): map of
     * {@code mendr:hostident:{host}} → JSON {@code {sourceService, tenantId}}.
     * Used when {@code X-Mendr-Key} is absent and host fallback is enabled.
     */
    private Map<String, String> hostIdentity;

    /**
     * AI gateway virtual routes for edges advertising {@code ai}:
     * map of {@code mendr:ai:route:{virtualPath}} → policy JSON.
     */
    private Map<String, String> aiRoutes;
}
