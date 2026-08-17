package com.selfhealing.gateway.model;

import com.selfhealing.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One upstream instance in a service's load-balancing pool.
 * {@link ServiceRegistration#getBaseUrl()} remains the single-URL back-compat fallback.
 */
@Entity
@Table(name = "service_instances")
@EntityListeners(com.selfhealing.gateway.tenant.TenantEntityListener.class)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceInstance implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Builder.Default
    @Column(nullable = false)
    private Integer weight = 100;

    private String zone;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "health_status")
    @Builder.Default
    private String healthStatus = "UNKNOWN";

    @Column(name = "last_health_check")
    private LocalDateTime lastHealthCheck;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (weight == null) weight = 100;
        if (healthStatus == null) healthStatus = "UNKNOWN";
        isActive = true;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
