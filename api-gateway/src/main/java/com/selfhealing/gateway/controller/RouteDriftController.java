package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.Tenant;
import com.selfhealing.gateway.repository.TenantRepository;
import com.selfhealing.gateway.service.OutboxRelay;
import com.selfhealing.gateway.service.RouteProgramReconciler;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class RouteDriftController {

    private final TenantRepository tenantRepository;
    private final RouteProgramReconciler reconciler;
    private final OutboxRelay outboxRelay;

    @GetMapping("/route-drift")
    public ResponseEntity<Map<String, Object>> routeDrift() {
        List<Map<String, Object>> drifted = new ArrayList<>();
        for (Tenant tenant : tenantRepository.findAll()) {
            TenantContext.setTenantId(tenant.getId());
            try {
                for (Map<String, Object> row : reconciler.listDriftedRoutesForCurrentTenant()) {
                    Map<String, Object> tagged = new LinkedHashMap<>(row);
                    tagged.put("tenantId", tenant.getId().toString());
                    drifted.add(tagged);
                }
            } finally {
                TenantContext.clear();
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("driftedRoutes", drifted);
        body.put("outbox", outboxRelay.backlogStats());
        return ResponseEntity.ok(body);
    }
}
