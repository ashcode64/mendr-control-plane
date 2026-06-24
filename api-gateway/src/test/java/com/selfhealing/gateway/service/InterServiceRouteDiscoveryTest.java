package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayOpenRestyProperties;
import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.repository.ResponseTransformationRuleRepository;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.repository.ServiceRouteRepository;
import com.selfhealing.gateway.repository.TransformationRuleRepository;
import com.selfhealing.gateway.service.InterServiceRouteDiscovery.RouteTriple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterServiceRouteDiscoveryTest {

    @Mock private TransformationRuleRepository transformationRuleRepository;
    @Mock private ResponseTransformationRuleRepository responseTransformationRuleRepository;
    @Mock private ServiceContractRepository serviceContractRepository;
    @Mock private ServiceRouteRepository serviceRouteRepository;

    private GatewayOpenRestyProperties properties;
    private InterServiceRouteDiscovery discovery;

    @BeforeEach
    void setUp() {
        properties = new GatewayOpenRestyProperties();
        discovery = new InterServiceRouteDiscovery(
                transformationRuleRepository,
                responseTransformationRuleRepository,
                serviceContractRepository,
                serviceRouteRepository,
                properties);
    }

    @Test
    void discoversDemoRoutesFromBaselineConfig() {
        GatewayOpenRestyProperties.BaselineRoute route = new GatewayOpenRestyProperties.BaselineRoute();
        route.setSourceService("order-service");
        route.setTargetService("payment-service");
        route.setEndpoint("/api/payments/process");
        properties.setBaselineRoutes(List.of(route));

        Set<RouteTriple> routes = discovery.discoverAll();

        assertThat(routes).contains(new RouteTriple(
                "order-service", "payment-service", "/api/payments/process"));
    }

    @Test
    void discoversExplicitManifestRoutesFromServiceRoutes() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"order-service", "payment-service", "/api/payments/charge"});
        when(serviceRouteRepository.findDistinctActiveRoutes()).thenReturn(rows);

        Set<RouteTriple> routes = discovery.discoverAll();

        assertThat(routes).contains(new RouteTriple(
                "order-service", "payment-service", "/api/payments/charge"));
    }

    @Test
    void doesNotInferRoutesFromContractsByDefault() {
        // Contract heuristic disabled by default — endpoint-name inference must not run.
        Set<RouteTriple> routes = discovery.discoverAll();

        assertThat(routes).isEmpty();
    }

    @Test
    void discoversCallerToProviderFromRequestContractsWhenHeuristicEnabled() {
        properties.setContractHeuristicRoutesEnabled(true);
        when(serviceContractRepository.findByIsActiveTrue()).thenReturn(List.of(
                contract("order-service", "/api/payments/process", "REQUEST"),
                contract("payment-service", "/api/payments/process", "REQUEST")));

        Set<RouteTriple> routes = discovery.discoverAll();

        assertThat(routes).contains(new RouteTriple(
                "order-service", "payment-service", "/api/payments/process"));
        assertThat(routes).doesNotContain(new RouteTriple(
                "payment-service", "order-service", "/api/payments/process"));
    }

    @Test
    void discoversResponseErrorRouteFromResponseContractsWhenHeuristicEnabled() {
        properties.setContractHeuristicRoutesEnabled(true);
        when(serviceContractRepository.findByIsActiveTrue()).thenReturn(List.of(
                contract("order-service", "/api/payments/process/response-error", "RESPONSE"),
                contract("payment-service", "/api/payments/process/response-error", "RESPONSE")));

        Set<RouteTriple> routes = discovery.discoverAll();

        assertThat(routes).contains(new RouteTriple(
                "order-service", "payment-service", "/api/payments/process/response-error"));
    }

    @Test
    void inferProviderMatchesEndpointNamespace() {
        assertThat(InterServiceRouteDiscovery.inferProvider(
                "/api/payments/process",
                Set.of("order-service", "payment-service"))).isEqualTo("payment-service");
    }

    @Test
    void discoverForServiceFiltersByParticipant() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"order-service", "payment-service", "/api/payments/process"});
        when(transformationRuleRepository.findDistinctActiveRoutes()).thenReturn(rows);

        Set<RouteTriple> paymentRoutes = discovery.discoverForService("payment-service");
        Set<RouteTriple> inventoryRoutes = discovery.discoverForService("inventory-service");

        assertThat(paymentRoutes).hasSize(1);
        assertThat(inventoryRoutes).isEmpty();
    }

    private static ServiceContract contract(String service, String endpoint, String direction) {
        ServiceContract contract = new ServiceContract();
        contract.setServiceName(service);
        contract.setEndpoint(endpoint);
        contract.setDirection(direction);
        contract.setHttpMethod("POST");
        contract.setActive(true);
        return contract;
    }
}
