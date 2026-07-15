package com.selfhealing.gateway.model;

import com.selfhealing.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "openapi_spec_registry")
@EntityListeners(com.selfhealing.gateway.tenant.TenantEntityListener.class)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OpenApiSpecRegistry implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "source_app", nullable = false)
    private String sourceApp;

    @Column(name = "spec_url")
    private String specUrl;

    @Column(name = "spec_hash", nullable = false)
    private String specHash;

    private String version;

    @Column(name = "ingress_host")
    private String ingressHost;

    @Column(name = "enforce_mode")
    @Builder.Default
    private String enforceMode = "observe";

    @Column(name = "raw_spec", columnDefinition = "text")
    private String rawSpec;

    private String etag;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @PrePersist
    protected void onCreate() {
        importedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        isActive = true;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
