package com.selfhealing.analysis.model;

import com.selfhealing.analysis.tenant.TenantScoped;
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
@EntityListeners(com.selfhealing.analysis.tenant.TenantEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "failure_id")
    private UUID failureId;

    @Column(name = "root_cause", nullable = false, columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "confidence", columnDefinition = "numeric")
    private Double confidence;

    /** Venn-Abers point estimate (may equal {@link #confidence} when fitted). */
    @Column(name = "calibrated_confidence", columnDefinition = "numeric")
    private Double calibratedConfidence;

    /** Epistemic interval width p1 − p0. */
    @Column(name = "confidence_interval_width", columnDefinition = "numeric")
    private Double confidenceIntervalWidth;

    @Column(name = "venn_abers_fitted")
    private Boolean vennAbersFitted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transformation_rules", columnDefinition = "jsonb")
    private Map<String, Object> transformationRules;

    @Column(name = "suggested_permanent_fix", columnDefinition = "TEXT")
    private String suggestedPermanentFix;

    @Column(name = "ai_model")
    private String aiModel;

    /** Provenance: was this a real Claude analysis or the mock fallback? */
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_source")
    private AnalysisSource analysisSource;

    /**
     * Audit-only metadata (allowlists, validation reason) kept OFF the deployed
     * {@code transformationRules} map. Persisted for the dashboard/audit trail,
     * never compiled into a route snapshot.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_metadata", columnDefinition = "jsonb")
    private Map<String, Object> analysisMetadata;

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

    public enum AnalysisSource {
        CLAUDE, GEMINI, MOCK
    }
}
