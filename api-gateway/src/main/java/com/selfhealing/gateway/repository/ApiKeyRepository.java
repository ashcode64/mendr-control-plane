package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    List<ApiKey> findByTenantId(UUID tenantId);
}
