package com.selfhealing.gateway.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cors_rules")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CorsRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "target_service", nullable = false)
    private String targetService;

    @Column(name = "allowed_origin", nullable = false)
    private String allowedOrigin;

    @Column(name = "previous_origin")
    private String previousOrigin;

    @Column(name = "failure_id")
    private UUID failureId;

    @Column(name = "analysis_id")
    private UUID analysisId;

    @Column(name = "allowed_methods")
    private String allowedMethods;

    @Column(name = "allowed_headers")
    private String allowedHeaders;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "is_active")
    private boolean isActive;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (allowedMethods == null) allowedMethods = "GET,POST,PUT,DELETE,OPTIONS";
        if (allowedHeaders == null) allowedHeaders = "*";
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
