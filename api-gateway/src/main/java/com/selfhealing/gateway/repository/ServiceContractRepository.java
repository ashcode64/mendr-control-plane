package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.ServiceContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceContractRepository extends JpaRepository<ServiceContract, UUID> {
    List<ServiceContract> findByServiceNameAndIsActiveTrue(String serviceName);
    List<ServiceContract> findByServiceNameAndEndpointAndIsActiveTrue(String serviceName, String endpoint);
    List<ServiceContract> findByServiceNameAndEndpointAndDirectionAndIsActiveTrue(
            String serviceName, String endpoint, String direction);

    List<ServiceContract> findByIsActiveTrue();

    Optional<ServiceContract> findByServiceNameAndEndpointAndHttpMethodAndDirectionAndVersion(
            String serviceName, String endpoint, String httpMethod, String direction, String version);
}
