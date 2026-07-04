package com.selfhealing.gateway.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "transformation_rules")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransformationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analysis_id")
    private UUID analysisId;

    @Column(name = "service_a", nullable = false)
    private String serviceA;

    @Column(name = "service_b", nullable = false)
    private String serviceB;

    @Column(name = "endpoint", nullable = false)
    private String endpoint;

    @Column(name = "rule_type")
    @Convert(converter = RuleTypeConverter.class)
    private RuleType ruleType;

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

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum RuleType {
        FIELD_RENAME,
        TYPE_COERCE,
        ADD_DEFAULT,
        REMOVE_FIELD,
        FIELD_MOVE,
        SCALE,
        COALESCE,
        MAP_VALUE,
        REFORMAT_DATE,
        STRIP_UNKNOWN,
        WRAP_ARRAY,
        UNWRAP_ARRAY,
        NESTED_TRANSFORM,
        /**
         * A full MendrScript program (closed-vocabulary AST) authored by the
         * conversation engine and verified before deploy. {@code ruleDefinition}
         * carries {@code {schemaVersion, ops:[...]}}. Compiled into snapshot v2
         * {@code ops[]} rather than the legacy buckets.
         */
        DSL_PROGRAM
    }
}
