package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.ServiceInstance;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.ServiceInstanceRepository;
import com.selfhealing.gateway.repository.ServiceRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceInstanceService {

    private final ServiceInstanceRepository instanceRepository;
    private final ServiceRegistrationRepository serviceRepository;
    private final RouteChangedPublisher routeChangedPublisher;

    public List<ServiceInstance> list(String serviceName) {
        ServiceRegistration svc = requireService(serviceName);
        return instanceRepository.findByServiceIdOrderByCreatedAtAsc(svc.getId());
    }

    @Transactional
    public ServiceInstance add(String serviceName, ServiceInstance instance) {
        ServiceRegistration svc = requireService(serviceName);
        instance.setId(null);
        instance.setServiceId(svc.getId());
        ServiceInstance saved = instanceRepository.save(instance);
        routeChangedPublisher.publishTargetService(serviceName);
        log.info("Added instance {} to service {}", saved.getBaseUrl(), serviceName);
        return saved;
    }

    @Transactional
    public ServiceInstance update(String serviceName, UUID instanceId, ServiceInstance patch) {
        requireService(serviceName);
        ServiceInstance existing = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));
        if (patch.getBaseUrl() != null) existing.setBaseUrl(patch.getBaseUrl());
        if (patch.getWeight() != null) existing.setWeight(patch.getWeight());
        if (patch.getZone() != null) existing.setZone(patch.getZone());
        existing.setActive(patch.isActive());
        if (patch.getHealthStatus() != null) existing.setHealthStatus(patch.getHealthStatus());
        if (patch.getMetadata() != null) existing.setMetadata(patch.getMetadata());
        ServiceInstance saved = instanceRepository.save(existing);
        routeChangedPublisher.publishTargetService(serviceName);
        return saved;
    }

    @Transactional
    public void remove(String serviceName, UUID instanceId) {
        requireService(serviceName);
        instanceRepository.deleteById(instanceId);
        routeChangedPublisher.publishTargetService(serviceName);
    }

    /**
     * Normalize a full request URL or base URL to scheme://host[:port] for pool matching.
     * Ejection callers often pass targetServiceUrl / attemptedUrl with a path suffix.
     */
    static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.trim();
        int scheme = u.indexOf("://");
        if (scheme < 0) return u.replaceAll("/+$", "");
        int pathStart = u.indexOf('/', scheme + 3);
        String base = pathStart > 0 ? u.substring(0, pathStart) : u;
        return base.replaceAll("/+$", "");
    }

    static boolean urlsMatch(String candidate, String instanceBase) {
        String a = normalizeBaseUrl(candidate);
        String b = normalizeBaseUrl(instanceBase);
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /** Autonomy hook: mark an instance unhealthy / eject from active pool. */
    @Transactional
    public Optional<ServiceInstance> eject(String serviceName, String baseUrl, String reason) {
        ServiceRegistration svc = requireService(serviceName);
        List<ServiceInstance> instances =
                instanceRepository.findByServiceIdAndIsActiveTrueOrderByWeightDesc(svc.getId());
        for (ServiceInstance i : instances) {
            if (urlsMatch(baseUrl, i.getBaseUrl())) {
                i.setHealthStatus("EJECTED");
                i.setActive(false);
                ServiceInstance saved = instanceRepository.save(i);
                routeChangedPublisher.publishTargetService(serviceName);
                log.warn("Ejected upstream {} from {} — {}", i.getBaseUrl(), serviceName, reason);
                return Optional.of(saved);
            }
        }
        // Also match inactive pool members (already soft-ejected) by URL for idempotency
        for (ServiceInstance i : instanceRepository.findByServiceIdOrderByCreatedAtAsc(svc.getId())) {
            if (urlsMatch(baseUrl, i.getBaseUrl()) && !"EJECTED".equalsIgnoreCase(i.getHealthStatus())) {
                i.setHealthStatus("EJECTED");
                i.setActive(false);
                ServiceInstance saved = instanceRepository.save(i);
                routeChangedPublisher.publishTargetService(serviceName);
                log.warn("Ejected upstream {} from {} — {}", i.getBaseUrl(), serviceName, reason);
                return Optional.of(saved);
            }
        }
        return Optional.empty();
    }

    private ServiceRegistration requireService(String name) {
        return serviceRepository.findByNameAndIsActiveTrue(name)
                .orElseThrow(() -> new IllegalArgumentException("Service not found: " + name));
    }
}
