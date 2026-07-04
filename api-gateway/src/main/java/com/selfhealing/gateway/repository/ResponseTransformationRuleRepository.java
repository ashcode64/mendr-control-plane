package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.ResponseTransformationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ResponseTransformationRuleRepository extends JpaRepository<ResponseTransformationRule, UUID> {

    List<ResponseTransformationRule> findByServiceAAndServiceBAndEndpointAndIsActiveTrue(
            String serviceA, String serviceB, String endpoint);

    @Query("SELECT r FROM ResponseTransformationRule r WHERE r.serviceA = :serviceA AND r.serviceB = :serviceB "
            + "AND r.endpoint = :endpoint AND r.isActive = true AND (r.expiresAt IS NULL OR r.expiresAt > :now) "
            + "ORDER BY r.approvedAt ASC, r.createdAt ASC")
    List<ResponseTransformationRule> findActiveNonExpiredForRoute(
            @Param("serviceA") String serviceA, @Param("serviceB") String serviceB,
            @Param("endpoint") String endpoint, @Param("now") LocalDateTime now);

    @Query("SELECT r FROM ResponseTransformationRule r WHERE r.isActive = true AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
    List<ResponseTransformationRule> findAllActiveAndNotExpired(LocalDateTime now);

    @Query("SELECT DISTINCT r.serviceA, r.serviceB, r.endpoint FROM ResponseTransformationRule r WHERE r.isActive = true")
    List<Object[]> findDistinctActiveRoutes();

    @Query("SELECT DISTINCT r.serviceA, r.serviceB, r.endpoint FROM ResponseTransformationRule r WHERE r.isActive = true AND (r.serviceA = :name OR r.serviceB = :name)")
    List<Object[]> findDistinctActiveRoutesForService(@Param("name") String name);

    @Query("SELECT r FROM ResponseTransformationRule r WHERE r.isActive = true AND r.expiresAt < :now")
    List<ResponseTransformationRule> findExpiredRules(LocalDateTime now);
}
