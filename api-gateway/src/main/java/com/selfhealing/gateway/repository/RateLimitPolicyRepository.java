package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.RateLimitPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RateLimitPolicyRepository extends JpaRepository<RateLimitPolicy, UUID> {

    List<RateLimitPolicy> findByEnabledTrue();

    List<RateLimitPolicy> findByServiceNameAndEnabledTrue(String serviceName);

    Optional<RateLimitPolicy> findByName(String name);

    List<RateLimitPolicy> findByServiceNameAndRouteEndpointAndEnabledTrue(
            String serviceName, String routeEndpoint);
}
