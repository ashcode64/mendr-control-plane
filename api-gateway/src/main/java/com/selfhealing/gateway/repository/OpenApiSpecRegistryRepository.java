package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.OpenApiSpecRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OpenApiSpecRegistryRepository extends JpaRepository<OpenApiSpecRegistry, UUID> {

    Optional<OpenApiSpecRegistry> findFirstBySourceAppAndIsActiveTrueOrderByImportedAtDesc(String sourceApp);

    Optional<OpenApiSpecRegistry> findBySourceAppAndSpecHashAndIsActiveTrue(String sourceApp, String specHash);
}
