package com.selfhealing.gateway.model;

import com.selfhealing.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "routing_rules")
@EntityListeners(com.selfhealing.gateway.tenant.TenantEntityListener.class)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RoutingRule implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "original_url", nullable = false)
    private String originalUrl;

    @Column(name = "new_url", nullable = false)
    private String newUrl;

    @Column(name = "discovery_method")
    private String discoveryMethod;   // DNS_PROBE, HEALTH_CHECK, MANUAL, AI_SUGGESTED

    @Column(name = "failure_id")
    private UUID failureId;

    @Column(name = "analysis_id")
    private UUID analysisId;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "is_active")
    private boolean isActive;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "probe_count")
    private int probeCount;

    @Column(name = "last_probed_at")
    private LocalDateTime lastProbedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
