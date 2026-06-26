package com.selfhealing.gateway.model;

import com.selfhealing.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "service_contracts")
@EntityListeners(com.selfhealing.gateway.tenant.TenantEntityListener.class)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceContract implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "http_method")
    private String httpMethod;

    /** REQUEST or RESPONSE */
    private String direction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "example_payload", columnDefinition = "jsonb")
    private Map<String, Object> examplePayload;

    /**
     * Lightweight inferred schema (required/optional/types/enums/array-shape) derived
     * from one or more example payloads at import time. Lets deterministic analyzers
     * tell required from optional instead of "differs from the one example".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inferred_schema", columnDefinition = "jsonb")
    private Map<String, Object> inferredSchema;

    private String description;
    private String version;

    @Column(name = "registered_by")
    private String registeredBy;

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
        if (direction == null)   direction = "REQUEST";
        if (httpMethod == null)  httpMethod = "POST";
        if (version == null)     version = "1.0";
        isActive = true;
    }

    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
