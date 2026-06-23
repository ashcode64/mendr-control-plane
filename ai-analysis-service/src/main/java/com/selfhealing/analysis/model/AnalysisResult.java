package com.selfhealing.analysis.model;

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
@Table(name = "analysis_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "failure_id")
    private UUID failureId;

    @Column(name = "root_cause", nullable = false, columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "confidence", columnDefinition = "numeric")
    private Double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transformation_rules", columnDefinition = "jsonb")
    private Map<String, Object> transformationRules;

    @Column(name = "suggested_permanent_fix", columnDefinition = "TEXT")
    private String suggestedPermanentFix;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Enumerated(EnumType.STRING)
    private AnalysisStatus status;

    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
        if (status == null) status = AnalysisStatus.PENDING_APPROVAL;
    }

    public enum AnalysisStatus {
        PENDING_APPROVAL, APPROVED, REJECTED
    }
}
