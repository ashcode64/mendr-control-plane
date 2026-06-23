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
@Table(name = "api_failures")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_a", nullable = false)
    private String serviceA;

    @Column(name = "service_b", nullable = false)
    private String serviceB;

    @Column(name = "endpoint", nullable = false)
    private String endpoint;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_type")
    private String errorType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private Map<String, Object> responsePayload;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "detected_at")
    private LocalDateTime detectedAt;

    @Enumerated(EnumType.STRING)
    private FailureStatus status;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    @PrePersist
    protected void onCreate() {
        detectedAt = LocalDateTime.now();
        if (status == null) status = FailureStatus.OPEN;
    }

    public enum FailureStatus {
        OPEN, ANALYZING, RESOLVED, IGNORED
    }
}
