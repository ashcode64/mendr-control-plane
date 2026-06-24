package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.ServiceRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceRouteRepository extends JpaRepository<ServiceRoute, UUID> {

    List<ServiceRoute> findByIsActiveTrue();

    Optional<ServiceRoute> findBySourceServiceAndTargetServiceAndEndpointAndHttpMethod(
            String sourceService, String targetService, String endpoint, String httpMethod);

    @Query("SELECT DISTINCT r.sourceService, r.targetService, r.endpoint FROM ServiceRoute r WHERE r.isActive = true")
    List<Object[]> findDistinctActiveRoutes();

    @Query("SELECT DISTINCT r.sourceService, r.targetService, r.endpoint FROM ServiceRoute r "
            + "WHERE r.isActive = true AND (r.sourceService = :name OR r.targetService = :name)")
    List<Object[]> findDistinctActiveRoutesForService(@Param("name") String name);
}
