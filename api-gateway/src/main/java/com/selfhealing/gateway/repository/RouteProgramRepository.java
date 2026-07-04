package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.RouteProgram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RouteProgramRepository
        extends JpaRepository<RouteProgram, RouteProgram.RouteKey> {

    Optional<RouteProgram> findBySourceServiceAndTargetServiceAndEndpoint(
            String sourceService, String targetService, String endpoint);
}
