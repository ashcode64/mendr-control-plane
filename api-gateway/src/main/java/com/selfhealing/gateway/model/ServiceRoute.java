package com.selfhealing.gateway.model;

import com.selfhealing.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Explicit, manifest-declared inter-service route: {@code sourceService} calls
 * {@code targetService} at {@code endpoint}. This is the canonical source of
 * inter-service routes for snapshot publishing, replacing heuristic
 * contract-name inference.
 */
@Entity
@Table(name = "service_routes")
@EntityListeners(com.selfhealing.gateway.tenant.TenantEntityListener.class)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceRoute implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "source_service", nullable = false)
    private String sourceService;

    @Column(name = "target_service", nullable = false)
    private String targetService;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "http_method")
    private String httpMethod;

    /** EXACT | PREFIX | TEMPLATE — only EXACT is honored by the edge today. */
    @Column(name = "match_type")
    private String matchType;

    private String description;

    @Column(name = "is_active")
    private boolean isActive;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (httpMethod == null) httpMethod = "POST";
        if (matchType == null)  matchType = "EXACT";
        isActive = true;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
