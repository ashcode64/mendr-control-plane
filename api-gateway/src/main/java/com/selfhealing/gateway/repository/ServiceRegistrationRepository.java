package com.selfhealing.gateway.repository;

import com.selfhealing.gateway.model.ServiceRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceRegistrationRepository extends JpaRepository<ServiceRegistration, UUID> {

    Optional<ServiceRegistration> findByNameAndIsActiveTrue(String name);

    Optional<ServiceRegistration> findByName(String name);

    List<ServiceRegistration> findAllByIsActiveTrue();

    List<ServiceRegistration> findAllByNamespaceAndIsActiveTrue(String namespace);

    @Query("SELECT s FROM ServiceRegistration s ORDER BY s.name ASC")
    List<ServiceRegistration> findAllOrderedByName();

    boolean existsByName(String name);
}
