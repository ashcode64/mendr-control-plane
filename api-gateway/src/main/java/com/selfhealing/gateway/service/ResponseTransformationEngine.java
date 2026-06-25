package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.model.ResponseTransformationRule;
import com.selfhealing.gateway.repository.ResponseTransformationRuleRepository;
import com.selfhealing.gateway.util.DefaultValueNormalizer;
import com.selfhealing.gateway.util.JsonPointer;
import com.selfhealing.gateway.util.TypeCoercer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors TransformationEngine but operates on RESPONSE bodies.
 *
 * Applied after Service B responds, before the gateway returns
 * the response to Service A. Handles:
 *
 *   RESPONSE_FIELD_MOVE   — relocate a field across nesting levels (applied first)
 *   RESPONSE_FIELD_RENAME — rename a field in the response body
 *   RESPONSE_TYPE_COERCE  — coerce a response field to correct type
 *   RESPONSE_ADD_DEFAULT  — add a missing field Service A expects
 *   RESPONSE_REMOVE_FIELD — strip fields Service A can't handle
 *   RESPONSE_WRAP         — wrap the whole response under a key
 *   RESPONSE_UNWRAP       — pull a nested object up to top level
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseTransformationEngine {

    private final ResponseTransformationRuleRepository ruleRepository;
    private final RedisTemplate<String, Object>        redisTemplate;
    private final ObjectMapper                         objectMapper;
    private final RouteChangedPublisher                routeChangedPublisher;

    private static final String CACHE_PREFIX    = "resp_rules:";
    private static final long   CACHE_TTL_SECS  = 60;

    private static final Map<ResponseTransformationRule.ResponseRuleType, Integer> RULE_PRIORITY = Map.of(
            ResponseTransformationRule.ResponseRuleType.RESPONSE_FIELD_MOVE, 0,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_FIELD_RENAME, 1,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_ADD_DEFAULT, 2,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_TYPE_COERCE, 3,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_REMOVE_FIELD, 4,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_WRAP, 5,
            ResponseTransformationRule.ResponseRuleType.RESPONSE_UNWRAP, 6
    );

    public Map<String, Object> applyResponseTransformations(
            String serviceA, String serviceB, String endpoint,
            Map<String, Object> responseBody) {

        List<ResponseTransformationRule> rules = getActiveRules(serviceA, serviceB, endpoint);
        if (rules.isEmpty()) return responseBody;

        Map<String, Object> transformed = new HashMap<>(responseBody);
        for (ResponseTransformationRule rule : rules) {
            transformed = applyRule(rule, transformed);
            log.debug("Applied response rule {} ({}) to response from {}", rule.getId(), rule.getRuleType(), serviceB);
        }
        return transformed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyRule(ResponseTransformationRule rule, Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>(body);
        Map<String, Object> def   = rule.getRuleDefinition();

        switch (rule.getRuleType()) {
            case RESPONSE_FIELD_MOVE -> applyMoves(result, def.get("moves"));
            case RESPONSE_FIELD_RENAME -> {
                Map<String, String> mappings = (Map<String, String>) def.get("mappings");
                if (mappings != null) {
                    mappings.forEach((oldKey, newKey) -> {
                        if (result.containsKey(oldKey)) result.put(newKey, result.remove(oldKey));
                    });
                }
            }
            case RESPONSE_ADD_DEFAULT -> {
                Map<String, Object> defaults = (Map<String, Object>) def.get("defaults");
                if (defaults != null) {
                    defaults.forEach((field, value) ->
                            result.putIfAbsent(field, DefaultValueNormalizer.normalize(value)));
                }
            }
            case RESPONSE_REMOVE_FIELD -> {
                List<String> fields = (List<String>) def.get("fields");
                if (fields != null) fields.forEach(result::remove);
            }
            case RESPONSE_TYPE_COERCE -> {
                Object coercionsObj = def.get("coercions");
                if (coercionsObj instanceof Map<?, ?> coercions) {
                    coercions.forEach((field, targetType) -> {
                        if (field != null && result.containsKey(field.toString())) {
                            String key = field.toString();
                            result.put(key, TypeCoercer.coerce(result.get(key), String.valueOf(targetType)));
                        }
                    });
                }
            }
            case RESPONSE_WRAP -> {
                // def: { "key": "data" } → wraps entire response under {"data": {...}}
                String wrapKey = (String) def.getOrDefault("key", "data");
                Map<String, Object> wrapped = new HashMap<>();
                wrapped.put(wrapKey, new HashMap<>(result));
                return wrapped;
            }
            case RESPONSE_UNWRAP -> {
                // def: { "key": "data" } → pulls result["data"] to top level
                String unwrapKey = (String) def.getOrDefault("key", "data");
                Object inner = result.get(unwrapKey);
                if (inner instanceof Map) {
                    return new HashMap<>((Map<String, Object>) inner);
                }
            }
            default -> log.warn("Unknown response rule type: {}", rule.getRuleType());
        }
        return result;
    }

    /**
     * Relocate fields across nesting levels (RESPONSE_FIELD_MOVE). Mirrors the request-side
     * {@code TransformationEngine.applyMoves} and the Lua edge {@code transform.lua} (moves
     * first, JSON-Pointer paths, prune empty parents).
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
    private List<ResponseTransformationRule> getActiveRules(String serviceA, String serviceB, String endpoint) {
        String cacheKey = CACHE_PREFIX + serviceA + ":" + serviceB + ":" + endpoint;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.convertValue(cached, objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, ResponseTransformationRule.class));
            } catch (Exception ignored) {}
        }
        List<ResponseTransformationRule> rules = ruleRepository
                .findByServiceAAndServiceBAndEndpointAndIsActiveTrue(serviceA, serviceB, endpoint);
        rules = new ArrayList<>(rules);
        rules.sort(Comparator.comparingInt(r -> RULE_PRIORITY.getOrDefault(r.getRuleType(), 99)));
        redisTemplate.opsForValue().set(cacheKey, rules, CACHE_TTL_SECS, TimeUnit.SECONDS);
        return rules;
    }

    public void evictCache(String serviceA, String serviceB, String endpoint) {
        redisTemplate.delete(CACHE_PREFIX + serviceA + ":" + serviceB + ":" + endpoint);
        routeChangedPublisher.publishRoute(serviceA, serviceB, endpoint);
    }

    @Scheduled(fixedDelay = 300_000)
    public void expireRules() {
        List<ResponseTransformationRule> expired = ruleRepository.findExpiredRules(LocalDateTime.now());
        for (ResponseTransformationRule rule : expired) {
            rule.setActive(false);
            ruleRepository.save(rule);
            evictCache(rule.getServiceA(), rule.getServiceB(), rule.getEndpoint());
        }
        if (!expired.isEmpty()) log.info("Expired {} response transformation rules", expired.size());
    }
}
