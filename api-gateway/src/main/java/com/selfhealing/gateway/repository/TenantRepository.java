package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByWorkosOrgId(String workosOrgId);

    Optional<Tenant> findBySlug(String slug);
}
