package com.selfhealing.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "response_transformation_rules")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ResponseTransformationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id")
    private UUID analysisId;

    @Column(name = "service_a", nullable = false)
    private String serviceA;

    @Column(name = "service_b", nullable = false)
    private String serviceB;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "rule_type")
    @Enumerated(EnumType.STRING)
    private ResponseRuleType ruleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_definition", columnDefinition = "jsonb")
    private Map<String, Object> ruleDefinition;

    private String description;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "is_active")
    private boolean isActive;

    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (version == null) version = 1;
    }

    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public enum ResponseRuleType {
        RESPONSE_FIELD_RENAME,
        RESPONSE_TYPE_COERCE,
        RESPONSE_ADD_DEFAULT,
        RESPONSE_REMOVE_FIELD,
        RESPONSE_FIELD_MOVE,    // relocate a response field across nesting levels
        RESPONSE_WRAP,      // wrap response body under a key
        RESPONSE_UNWRAP     // unwrap a nested object to top-level
    }
}
