package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayOpenRestyProperties;
import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.repository.ResponseTransformationRuleRepository;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.repository.TransformationRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovers inter-service routes for Redis snapshot publishing — from active rules,
 * registered contracts, and configured baseline routes.
 */
@Service
@RequiredArgsConstructor
public class InterServiceRouteDiscovery {

    private final TransformationRuleRepository transformationRuleRepository;
    private final ResponseTransformationRuleRepository responseTransformationRuleRepository;
    private final ServiceContractRepository serviceContractRepository;
    private final GatewayOpenRestyProperties openRestyProperties;

    public Set<RouteTriple> discoverAll() {
        Set<RouteTriple> routes = new HashSet<>();
        routes.addAll(fromTransformationRules());
        routes.addAll(fromResponseRules());
        routes.addAll(fromContracts());
        routes.addAll(fromBaselineConfig());
        return routes;
    }

    public Set<RouteTriple> discoverForService(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Set.of();
        }
        return discoverAll().stream()
                .filter(r -> serviceName.equals(r.source()) || serviceName.equals(r.target()))
                .collect(Collectors.toSet());
    }

    private Set<RouteTriple> fromTransformationRules() {
        return distinctRows(transformationRuleRepository.findDistinctActiveRoutes());
    }

    private Set<RouteTriple> fromResponseRules() {
        return distinctRows(responseTransformationRuleRepository.findDistinctActiveRoutes());
    }

    private Set<RouteTriple> fromBaselineConfig() {
        Set<RouteTriple> routes = new HashSet<>();
        if (openRestyProperties.getBaselineRoutes() == null) {
            return routes;
        }
        for (GatewayOpenRestyProperties.BaselineRoute baseline : openRestyProperties.getBaselineRoutes()) {
            if (isBlank(baseline.getSourceService())
                    || isBlank(baseline.getTargetService())
                    || isBlank(baseline.getEndpoint())) {
                continue;
            }
            routes.add(new RouteTriple(
                    baseline.getSourceService(),
                    baseline.getTargetService(),
                    baseline.getEndpoint()));
        }
        return routes;
    }

    private Set<RouteTriple> fromContracts() {
        List<ServiceContract> contracts = serviceContractRepository.findByIsActiveTrue();
        Map<String, List<ServiceContract>> byEndpoint = contracts.stream()
                .collect(Collectors.groupingBy(ServiceContract::getEndpoint));

        Set<RouteTriple> routes = new HashSet<>();
        for (Map.Entry<String, List<ServiceContract>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<ServiceContract> onEndpoint = entry.getValue();

            Set<String> allServices = onEndpoint.stream()
                    .map(ServiceContract::getServiceName)
                    .collect(Collectors.toSet());

            String provider = inferProvider(endpoint, allServices);
            if (provider == null) {
                continue;
            }

            Set<String> requestCallers = onEndpoint.stream()
                    .filter(c -> "REQUEST".equalsIgnoreCase(c.getDirection()))
                    .map(ServiceContract::getServiceName)
                    .filter(name -> !provider.equals(name))
                    .collect(Collectors.toSet());

            for (String source : requestCallers) {
                routes.add(new RouteTriple(source, provider, endpoint));
            }

            Set<String> responseCallers = onEndpoint.stream()
                    .filter(c -> "RESPONSE".equalsIgnoreCase(c.getDirection()))
                    .map(ServiceContract::getServiceName)
                    .filter(name -> !provider.equals(name))
                    .collect(Collectors.toSet());

            for (String source : responseCallers) {
                routes.add(new RouteTriple(source, provider, endpoint));
            }
        }
        return routes;
    }

    /**
     * Infers the downstream provider for an endpoint from service names
     * (e.g. {@code /api/payments/process} → {@code payment-service}).
     */
    static String inferProvider(String endpoint, Set<String> candidates) {
        String best = null;
        int bestStemLength = -1;
        for (String service : candidates) {
            String stem = service.toLowerCase(Locale.ROOT)
                    .replace("-service", "")
                    .replace("-svc", "");
            if (stem.isBlank()) {
                continue;
            }
            if (endpoint.toLowerCase(Locale.ROOT).contains(stem) && stem.length() > bestStemLength) {
                best = service;
                bestStemLength = stem.length();
            }
        }
        return best;
    }

    private static Set<RouteTriple> distinctRows(List<Object[]> rows) {
        Set<RouteTriple> routes = new HashSet<>();
        if (rows == null) {
            return routes;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 3) {
                continue;
            }
            routes.add(new RouteTriple(
                    String.valueOf(row[0]),
                    String.valueOf(row[1]),
                    String.valueOf(row[2])));
        }
        return routes;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record RouteTriple(String source, String target, String endpoint) {}
}
