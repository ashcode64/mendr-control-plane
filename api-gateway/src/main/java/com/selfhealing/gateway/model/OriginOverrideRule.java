package com.selfhealing.gateway.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "origin_override_rules")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OriginOverrideRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_service", nullable = false)
    private String sourceService;

    @Column(name = "target_service", nullable = false)
    private String targetService;

    @Column(nullable = false)
    private String endpoint;

    @Column(name = "caller_origin", nullable = false)
    private String callerOrigin;

    @Column(name = "outbound_origin", nullable = false)
    private String outboundOrigin;

    @Column(name = "rewrite_response_acao")
    private boolean rewriteResponseAcao;

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
