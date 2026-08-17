package com.selfhealing.gateway.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A per-tenant API key for machine/edge auth. Looked up by {@code keyPrefix};
 * the secret is verified against {@code keyHash} (sha256 hex). Not under RLS,
 * because lookup happens before a tenant context exists — the high-entropy
 * secret is the protection, and the row's {@code tenantId} sets the context.
 */
@Entity
@Table(name = "api_keys")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    private String name;

    @Column(name = "key_prefix")
    private String keyPrefix;

    @Column(name = "key_hash")
    private String keyHash;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Optional OAuth-style scopes for edge authPolicy.requiredScopes checks. */
    @Column(name = "scopes", columnDefinition = "text[]")
    private String[] scopes;
}
