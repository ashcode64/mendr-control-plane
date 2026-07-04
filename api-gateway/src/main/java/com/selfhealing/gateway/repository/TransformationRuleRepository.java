package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.TransformationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransformationRuleRepository extends JpaRepository<TransformationRule, UUID> {

    List<TransformationRule> findByServiceAAndServiceBAndEndpointAndIsActiveTrue(
            String serviceA, String serviceB, String endpoint);

    @Query("SELECT r FROM TransformationRule r WHERE r.serviceA = :serviceA AND r.serviceB = :serviceB "
            + "AND r.endpoint = :endpoint AND r.isActive = true AND (r.expiresAt IS NULL OR r.expiresAt > :now) "
            + "ORDER BY r.approvedAt ASC, r.createdAt ASC")
    List<TransformationRule> findActiveNonExpiredForRoute(
            @Param("serviceA") String serviceA, @Param("serviceB") String serviceB,
            @Param("endpoint") String endpoint, @Param("now") LocalDateTime now);

    List<TransformationRule> findByIsActiveTrue();

    @Query("SELECT r FROM TransformationRule r WHERE r.isActive = true AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
    List<TransformationRule> findAllActiveAndNotExpired(LocalDateTime now);

    @Query("SELECT DISTINCT r.serviceA, r.serviceB, r.endpoint FROM TransformationRule r WHERE r.isActive = true")
    List<Object[]> findDistinctActiveRoutes();

    @Query("SELECT DISTINCT r.serviceA, r.serviceB, r.endpoint FROM TransformationRule r WHERE r.isActive = true AND (r.serviceA = :name OR r.serviceB = :name)")
    List<Object[]> findDistinctActiveRoutesForService(@Param("name") String name);

    @Query("SELECT r FROM TransformationRule r WHERE r.isActive = true AND r.expiresAt < :now")
    List<TransformationRule> findExpiredRules(LocalDateTime now);
}
