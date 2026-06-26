package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.CorsRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CorsRuleRepository extends JpaRepository<CorsRule, UUID> {
    List<CorsRule> findByTargetServiceAndIsActiveTrue(String targetService);
    Optional<CorsRule> findByTargetServiceAndAllowedOriginAndIsActiveTrue(String targetService, String origin);
    List<CorsRule> findAllByIsActiveTrue();
    boolean existsByTargetServiceAndAllowedOriginAndIsActiveTrue(String targetService, String origin);

    List<CorsRule> findAllByIsActiveTrueAndExpiresAtBefore(LocalDateTime now);
}
