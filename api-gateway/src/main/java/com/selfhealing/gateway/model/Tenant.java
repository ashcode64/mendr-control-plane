package com.selfhealing.gateway.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** A tenant (maps 1:1 to a WorkOS Organization). Not subject to RLS. */
@Entity
@Table(name = "tenants")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workos_org_id")
    private String workosOrgId;

    private String slug;

    private String name;

    private String plan;

    private String status;

    /** Plan-tier requests-per-minute quota (monetization foundation). */
    @Column(name = "quota_rpm")
    private Integer quotaRpm;

    /** Plan-tier requests-per-day quota. */
    @Column(name = "quota_rpd")
    private Integer quotaRpd;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "quota_metadata")
    private java.util.Map<String, Object> quotaMetadata;
}
