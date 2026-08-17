package com.selfhealing.gateway.model;

import com.selfhealing.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "services")
@EntityListeners(com.selfhealing.gateway.tenant.TenantEntityListener.class)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceRegistration implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "base_url")
    private String baseUrl;

    private String description;

    @Column(name = "team_email")
    private String teamEmail;

    /** Kubernetes namespace, e.g. "default", "production", "payments" */
    private String namespace;

    /** k8s Service object name if different from `name` */
    @Column(name = "k8s_service_name")
    private String k8sServiceName;

    /** Health check path, default /actuator/health */
    @Column(name = "health_endpoint")
    private String healthEndpoint;

    @Column(name = "auth_type")
    @Enumerated(EnumType.STRING)
    private AuthType authType;

    /** Header name for auth, e.g. "Authorization" or "X-Api-Key" */
    @Column(name = "auth_header_name")
    private String authHeaderName;

    /**
     * Name of the environment variable that holds the secret.
     * The gateway reads System.getenv(authSecretRef) at request time.
     * The actual secret is NEVER stored here.
     */
    @Column(name = "auth_secret_ref")
    private String authSecretRef;

    @Column(name = "timeout_ms")
    private Integer timeoutMs;

    @Column(name = "retry_count")
    private Integer retryCount;

    /** ROUND_ROBIN | WEIGHTED | CONSISTENT_HASH — used when service_instances exist. */
    @Column(name = "load_balance_algorithm")
    private String loadBalanceAlgorithm;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "circuit_breaker_json")
    private java.util.Map<String, Object> circuitBreakerJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_policy_json")
    private java.util.Map<String, Object> retryPolicyJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cache_policy_json")
    private java.util.Map<String, Object> cachePolicyJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth_policy_json")
    private java.util.Map<String, Object> authPolicyJson;

    /** HTTP | HTTP2 | GRPC | WEBSOCKET */
    @Column(name = "protocol")
    private String protocol;

    /** Origins allowed to call this service via Mendr (enforced as CORS rules on the edge). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_caller_origins")
    @Builder.Default
    private List<String> allowedCallerOrigins = new ArrayList<>();

    @Column(name = "is_active")
    private boolean isActive;

    @Column(name = "last_health_check")
    private LocalDateTime lastHealthCheck;

    @Column(name = "last_health_status")
    private String lastHealthStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (authType == null)     authType = AuthType.NONE;
        if (timeoutMs == null)    timeoutMs = 10000;
        if (retryCount == null)   retryCount = 2;
        if (healthEndpoint == null) healthEndpoint = "/actuator/health";
        if (namespace == null)    namespace = "default";
        if (loadBalanceAlgorithm == null) loadBalanceAlgorithm = "ROUND_ROBIN";
        if (protocol == null)     protocol = "HTTP";
        isActive = true;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public enum AuthType {
        NONE,
        JWT_BEARER,
        API_KEY_HEADER,
        API_KEY_QUERY,
        BASIC
    }

    /** Build k8s DNS name: service.namespace.svc.cluster.local */
    public String getK8sDns() {
        String svcName = k8sServiceName != null ? k8sServiceName : name;
        return svcName + "." + namespace + ".svc.cluster.local";
    }
}
