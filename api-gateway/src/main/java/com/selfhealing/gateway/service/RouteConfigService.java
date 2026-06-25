package com.selfhealing.gateway.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.selfhealing.gateway.config.GatewayFastPathProperties;
import com.selfhealing.gateway.model.CorsRule;
import com.selfhealing.gateway.model.ResponseTransformationRule;
import com.selfhealing.gateway.model.RouteConfig;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.model.TransformationRule;
import com.selfhealing.gateway.transform.TransformProgram;
import com.selfhealing.gateway.transform.TransformProgramCompiler;
import com.selfhealing.gateway.model.OriginOverrideRule;
import com.selfhealing.gateway.repository.CorsRuleRepository;
import com.selfhealing.gateway.repository.OriginOverrideRuleRepository;
import com.selfhealing.gateway.repository.ResponseTransformationRuleRepository;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.repository.ServiceRegistrationRepository;
import com.selfhealing.gateway.repository.TransformationRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteConfigService {

    private static final Map<ResponseTransformationRule.ResponseRuleType, Integer> RESPONSE_RULE_PRIORITY = Map.of(
            ResponseTransformationRule.ResponseRuleType.RESPONSE_FIELD_MOVE, 0,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_FIELD_RENAME, 1,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_ADD_DEFAULT, 2,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_TYPE_COERCE, 3,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_REMOVE_FIELD, 4,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_WRAP, 5,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_UNWRAP, 6
    );

    private final GatewayFastPathProperties properties;
    private final DynamicRoutingService routingService;
    private final ServiceRegistryService registryService;
    private final ServiceRegistrationRepository serviceRegistrationRepository;
    private final TransformationRuleRepository transformationRuleRepository;
    private final ResponseTransformationRuleRepository responseTransformationRuleRepository;
    private final CorsRuleRepository corsRuleRepository;
    private final OriginOverrideRuleRepository originOverrideRuleRepository;
    private final ServiceContractRepository serviceContractRepository;
    private final TransformProgramCompiler programCompiler;

    private Cache<String, RouteConfig> l1Cache;

    @PostConstruct
    void initCache() {
        l1Cache = Caffeine.newBuilder()
                .maximumSize(properties.getL1MaxSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getL1TtlSeconds()))
                .build();
    }

    public static String routeKey(String sourceService, String targetService, String endpoint) {
        return sourceService + ":" + targetService + ":" + endpoint;
    }

    public RouteConfig get(String sourceService, String targetService, String endpoint) {
        String key = routeKey(sourceService, targetService, endpoint);
        RouteConfig cached = l1Cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        RouteConfig assembled = assemble(sourceService, targetService, endpoint);
        l1Cache.put(key, assembled);
        return assembled;
    }

    public void evictRoute(String sourceService, String targetService, String endpoint) {
        l1Cache.invalidate(routeKey(sourceService, targetService, endpoint));
    }

    public void evictByTargetService(String targetService) {
        l1Cache.asMap().keySet().removeIf(key -> key.contains(":" + targetService + ":"));
    }

    public void evictAll() {
        l1Cache.invalidateAll();
    }

    /**
     * Handles pub/sub invalidation messages.
     * Keys: full route key, {@code target:{serviceName}}, or {@code *} for all.
     */
    public void handleInvalidationMessage(String message) {
        if (message == null || message.isBlank()) return;
        if ("*".equals(message)) {
            evictAll();
            return;
        }
        if (message.startsWith("target:")) {
            evictByTargetService(message.substring("target:".length()));
            return;
        }
        l1Cache.invalidate(message);
    }

    private RouteConfig assemble(String sourceService, String targetService, String endpoint) {
        String resolvedBase = routingService.resolveUrl(targetService);
        if (resolvedBase == null || resolvedBase.isBlank()) {
            resolvedBase = registryService.resolveBaseUrl(targetService);
        }

        Optional<ServiceRegistration> registration =
                serviceRegistrationRepository.findByNameAndIsActiveTrue(targetService);

        String registeredBase = registration.map(ServiceRegistration::getBaseUrl).orElse(null);

        List<TransformationRule> requestRules = transformationRuleRepository
                .findByServiceAAndServiceBAndEndpointAndIsActiveTrue(sourceService, targetService, endpoint);
        List<ResponseTransformationRule> responseRules = new java.util.ArrayList<>(
                responseTransformationRuleRepository
                        .findByServiceAAndServiceBAndEndpointAndIsActiveTrue(sourceService, targetService, endpoint));
        responseRules.sort(Comparator.comparingInt(r -> RESPONSE_RULE_PRIORITY.getOrDefault(r.getRuleType(), 99)));

        List<CorsRule> corsRules = corsRuleRepository.findByTargetServiceAndIsActiveTrue(targetService);
        Set<String> allowedOrigins = new HashSet<>();
        for (CorsRule rule : corsRules) {
            allowedOrigins.add(rule.getAllowedOrigin());
        }

        boolean hasResponseContract = !serviceContractRepository
                .findByServiceNameAndEndpointAndDirectionAndIsActiveTrue(sourceService, endpoint, "RESPONSE")
                .isEmpty();

        ServiceRegistration reg = registration.orElse(null);
        ServiceRegistration.AuthType authType = reg != null && reg.getAuthType() != null
                ? reg.getAuthType() : ServiceRegistration.AuthType.NONE;

        TransformProgram requestProgram = programCompiler.compileRequest(requestRules);
        TransformProgram responseProgram = programCompiler.compileResponse(responseRules);

        List<OriginOverrideRule> overrideRules = originOverrideRuleRepository
                .findBySourceServiceAndTargetServiceAndEndpointAndIsActiveTrue(
                        sourceService, targetService, endpoint);
        List<RouteConfig.OriginOverrideSpec> originOverrides = new ArrayList<>();
        for (OriginOverrideRule rule : overrideRules) {
            originOverrides.add(new RouteConfig.OriginOverrideSpec(
                    rule.getCallerOrigin(),
                    rule.getOutboundOrigin(),
                    rule.isRewriteResponseAcao()));
        }

        RouteConfig config = RouteConfig.builder()
                .sourceService(sourceService)
                .targetService(targetService)
                .endpoint(endpoint)
                .targetBaseUrl(resolvedBase)
                .registeredBaseUrl(registeredBase)
                .authType(authType)
                .authHeaderName(reg != null ? reg.getAuthHeaderName() : null)
                .authSecretRef(reg != null ? reg.getAuthSecretRef() : null)
                .hasRequestRules(!requestRules.isEmpty())
                .requestRules(List.copyOf(requestRules))
                .hasResponseRules(!responseRules.isEmpty())
                .responseRules(List.copyOf(responseRules))
                .corsActive(!corsRules.isEmpty())
                .allowedOrigins(Set.copyOf(allowedOrigins))
                .hasResponseContract(hasResponseContract)
                .requestProgram(requestProgram)
                .responseProgram(responseProgram)
                .originOverrides(List.copyOf(originOverrides))
                .build();

        log.debug("Assembled RouteConfig {} → {} targetBaseUrl={} registeredBaseUrl={} corsActive={} streamable={}",
                sourceService, targetService, resolvedBase, registeredBase, !corsRules.isEmpty(),
                requestProgram.isStreamable() && responseProgram.isStreamable());

        return config;
    }
}
