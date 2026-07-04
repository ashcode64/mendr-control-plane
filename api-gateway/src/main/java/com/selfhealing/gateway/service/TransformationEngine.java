package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.model.TransformationRule;
import com.selfhealing.gateway.repository.TransformationRuleRepository;
import com.selfhealing.gateway.util.DefaultValueNormalizer;
import com.selfhealing.gateway.util.JsonPointer;
import com.selfhealing.gateway.util.TypeCoercer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransformationEngine {

    private final TransformationRuleRepository ruleRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RouteChangedPublisher routeChangedPublisher;

    private static final String RULE_CACHE_PREFIX = "rules:";
    private static final long CACHE_TTL_SECONDS = 60;

    /**
     * Apply all active transformation rules to the given payload
     * for a specific service-pair/endpoint combination.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> applyTransformations(
            String serviceA, String serviceB, String endpoint,
            Map<String, Object> payload) {

        List<TransformationRule> rules = getActiveRules(serviceA, serviceB, endpoint);
        if (rules.isEmpty()) {
            return payload;
        }

        Map<String, Object> transformed = new HashMap<>(payload);
        for (TransformationRule rule : rules) {
            transformed = applyRule(rule, transformed);
            log.debug("Applied rule {} ({}) to payload", rule.getId(), rule.getRuleType());
        }
        return transformed;
    }

    /**
     * Applies every section present in {@code rule_definition} in a safe order:
     * rename → add defaults → coerce types → remove fields.
     * Supports composite AI rules (NESTED_TRANSFORM) and single-type rules alike.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyRule(TransformationRule rule, Map<String, Object> payload) {
        Map<String, Object> result = new HashMap<>(payload);
        Map<String, Object> def = rule.getRuleDefinition();
        if (def == null || def.isEmpty()) {
            return result;
        }

        applyMoves(result, def.get("moves"));
        applyFieldRenames(result, def.get("mappings"));
        applyDefaults(result, def.get("defaults"));
        applyTypeCoercions(result, def.get("coercions"));
        applyRemovals(result, def.get("fields"));

        return result;
    }

    /**
     * FIELD_MOVE: relocate a value across nesting levels via JSON Pointers.
     * Mirrors the Lua edge {@code transform.apply_program} move step exactly.
     */
    @SuppressWarnings("unchecked")
    private void applyMoves(Map<String, Object> result, Object movesObj) {
        if (!(movesObj instanceof List<?> moves)) return;
        for (Object item : moves) {
            if (!(item instanceof Map<?, ?> mv)) continue;
            Object from = mv.get("from");
            Object to = mv.get("to");
            if (from == null || to == null) continue;
            boolean copy = mv.get("copy") instanceof Boolean b && b;
            String[] fromTokens = JsonPointer.split(from.toString());
            String[] toTokens = JsonPointer.split(to.toString());
            if (fromTokens == null || toTokens == null) continue;
            Object value = JsonPointer.get(result, fromTokens);
            if (value == null) continue;
            JsonPointer.set(result, toTokens, value);
            if (!copy) {
                JsonPointer.delete(result, fromTokens);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyFieldRenames(Map<String, Object> result, Object mappingsObj) {
        if (!(mappingsObj instanceof Map<?, ?> mappings)) return;
        mappings.forEach((oldKey, newKey) -> {
            if (oldKey == null || newKey == null) return;
            String from = oldKey.toString();
            String to = newKey.toString();
            if (result.containsKey(from)) {
                result.put(to, result.remove(from));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void applyDefaults(Map<String, Object> result, Object defaultsObj) {
        if (!(defaultsObj instanceof Map<?, ?> defaults)) return;
        defaults.forEach((field, value) -> {
            if (field == null) return;
            String key = field.toString();
            result.putIfAbsent(key, DefaultValueNormalizer.normalize(value));
        });
    }

    private void applyTypeCoercions(Map<String, Object> result, Object coercionsObj) {
        if (!(coercionsObj instanceof Map<?, ?> coercions)) return;
        coercions.forEach((field, targetType) -> {
            if (field == null) return;
            String key = field.toString();
            if (result.containsKey(key)) {
                result.put(key, TypeCoercer.coerce(result.get(key), String.valueOf(targetType)));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void applyRemovals(Map<String, Object> result, Object fieldsObj) {
        if (fieldsObj instanceof List<?> fields) {
            fields.forEach(field -> {
                if (field != null) result.remove(field.toString());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private List<TransformationRule> getActiveRules(String serviceA, String serviceB, String endpoint) {
        String cacheKey = com.selfhealing.gateway.tenant.TenantKeys.scoped(
                RULE_CACHE_PREFIX + serviceA + ":" + serviceB + ":" + endpoint);
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.convertValue(cached, objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, TransformationRule.class));
            } catch (Exception e) {
                log.warn("Cache deserialization failed, fetching from DB");
            }
        }

        List<TransformationRule> rules = ruleRepository
                .findByServiceAAndServiceBAndEndpointAndIsActiveTrue(serviceA, serviceB, endpoint);

        redisTemplate.opsForValue().set(cacheKey, rules, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return rules;
    }

    /** Evict cache for a specific route when rules change */
    public void evictRuleCache(String serviceA, String serviceB, String endpoint) {
        String cacheKey = com.selfhealing.gateway.tenant.TenantKeys.scoped(
                RULE_CACHE_PREFIX + serviceA + ":" + serviceB + ":" + endpoint);
        redisTemplate.delete(cacheKey);
        routeChangedPublisher.publishRoute(serviceA, serviceB, endpoint);
    }

    /**
     * Expire TTL-based request transformation rules for the current tenant
     * context. Deactivates each expired rule and evicts/republishes its route so
     * the edge drops it. Scheduling is owned by {@link RuleExpirySweeper} (which
     * binds the tenant context); call this directly only with a tenant bound.
     *
     * @return number of rules expired
     */
    public int expireRules() {
        List<TransformationRule> expired = ruleRepository.findExpiredRules(LocalDateTime.now());
        for (TransformationRule rule : expired) {
            rule.setActive(false);
            ruleRepository.save(rule);
            evictRuleCache(rule.getServiceA(), rule.getServiceB(), rule.getEndpoint());
            log.info("Expired transformation rule: {} for {}->{}", rule.getId(), rule.getServiceA(), rule.getServiceB());
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} transformation rules", expired.size());
        }
        return expired.size();
    }
}
