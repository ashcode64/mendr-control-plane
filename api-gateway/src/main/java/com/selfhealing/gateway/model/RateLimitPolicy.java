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
 * Control-plane authored rate / quota policy projected to the edge as {@code rateLimitPolicy}.
 */
@Entity
@Table(name = "rate_limit_policies")
@EntityListeners(com.selfhealing.gateway.tenant.TenantEntityListener.class)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RateLimitPolicy implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    /** TENANT | CONSUMER | ROUTE | SERVICE */
    @Builder.Default
    @Column(nullable = false)
    private String scope = "ROUTE";

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "route_endpoint")
    private String routeEndpoint;

    /** TOKEN_BUCKET | SLIDING_WINDOW | FIXED_WINDOW */
    @Builder.Default
    @Column(nullable = false)
    private String algorithm = "SLIDING_WINDOW";

    @Column(name = "requests_per_second")
    private Double requestsPerSecond;

    @Column(name = "requests_per_minute")
    private Integer requestsPerMinute;

    @Builder.Default
    private Integer burst = 0;

    @Column(name = "consumer_key")
    private String consumerKey;

    @Column(name = "plan_tier")
    private String planTier;

    @Builder.Default
    private boolean enabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
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
        if (scope == null) scope = "ROUTE";
        if (algorithm == null) algorithm = "SLIDING_WINDOW";
        if (burst == null) burst = 0;
        enabled = true;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
