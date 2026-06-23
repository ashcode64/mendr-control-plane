package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.RoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoutingRuleRepository extends JpaRepository<RoutingRule, UUID> {

    Optional<RoutingRule> findByServiceNameAndIsActiveTrue(String serviceName);

    List<RoutingRule> findAllByIsActiveTrueAndExpiresAtAfter(LocalDateTime now);

    List<RoutingRule> findAllByIsActiveTrueAndExpiresAtBefore(LocalDateTime now);

    @Query("SELECT r FROM RoutingRule r ORDER BY r.createdAt DESC")
    List<RoutingRule> findAllOrderByCreatedAtDesc();

    @Query("SELECT r FROM RoutingRule r WHERE r.isActive = true AND (r.expiresAt IS NULL OR r.expiresAt > :now) ORDER BY r.createdAt DESC")
    List<RoutingRule> findAllActiveAndNotExpired(LocalDateTime now);

    boolean existsByServiceNameAndNewUrlAndIsActiveTrue(String serviceName, String newUrl);
}
