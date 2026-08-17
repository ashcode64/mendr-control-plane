package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.ServiceInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstance, UUID> {

    List<ServiceInstance> findByServiceIdAndIsActiveTrueOrderByWeightDesc(UUID serviceId);

    List<ServiceInstance> findByServiceIdOrderByCreatedAtAsc(UUID serviceId);

    void deleteByServiceId(UUID serviceId);
}
