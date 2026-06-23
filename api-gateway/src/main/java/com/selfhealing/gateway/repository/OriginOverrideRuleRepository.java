package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.OriginOverrideRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OriginOverrideRuleRepository extends JpaRepository<OriginOverrideRule, UUID> {

    List<OriginOverrideRule> findBySourceServiceAndTargetServiceAndEndpointAndIsActiveTrue(
            String sourceService, String targetService, String endpoint);

    List<OriginOverrideRule> findAllByIsActiveTrue();
}
