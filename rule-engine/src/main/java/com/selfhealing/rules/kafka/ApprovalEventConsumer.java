package com.selfhealing.rules.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.selfhealing.rules.util.RoutingUrlResolver;
import com.selfhealing.rules.service.RouteChangedPublisher;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Consumes approval events and deploys the correct rule type:
 *
 *   FIELD_RENAME / ADD_DEFAULT / TYPE_COERCE / REMOVE_FIELD
 *       → transformation_rules table + gateway cache eviction
 *
 *   ROUTING_OVERRIDE
 *       → routing_rules table + route cache update + services table update
 *
 *   CORS_ALLOW
 *       → cors_rules table + CORS cache update
 *
 *   CORS_ORIGIN_OVERRIDE
 *       → origin_override_rules table + route snapshot republish
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalEventConsumer {

    private final JdbcTemplate          jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper          objectMapper;
    private final RouteChangedPublisher routeChangedPublisher;

    @Value("${rules.default-ttl-hours:48}")
    private int defaultTtlHours;

    @KafkaListener(topics = "api.transformations.approved", groupId = "rule-engine-group")
    @SuppressWarnings("unchecked")
    public void onApproved(@Payload Map<String, Object> event) {
        String analysisIdStr = str(event.get("analysisId"));
        String failureIdStr  = str(event.get("failureId"));
        String action        = str(event.get("action"));
        String actedBy       = str(event.getOrDefault("actedBy", "system"));

        if (!"APPROVED".equals(action)) return;

        Map<String, Object> rules = (Map<String, Object>) event.get("transformationRules");
        if (rules == null || rules.isEmpty()) {
            log.warn("No transformationRules in approval event — skipping");
            return;
        }
        rules.remove("_analysisMetadata");

        String ruleType = normalizeRuleType(str(rules.getOrDefault("type", "FIELD_RENAME")));
        log.info("Deploying approved rule type={} analysisId={}", ruleType, analysisIdStr);

        try {
            switch (ruleType) {
                case "ROUTING_OVERRIDE"       -> deployRoutingRule(rules, analysisIdStr, failureIdStr, actedBy);
                case "CORS_ALLOW"             -> deployCorsRule(rules, analysisIdStr, failureIdStr, actedBy);
                case "CORS_ORIGIN_OVERRIDE"   -> deployOriginOverrideRule(rules, analysisIdStr, failureIdStr, actedBy);
                case "RESPONSE_FIELD_RENAME",
                     "RESPONSE_TYPE_COERCE",
                     "RESPONSE_ADD_DEFAULT",
                     "RESPONSE_REMOVE_FIELD",
                     "RESPONSE_WRAP",
                     "RESPONSE_UNWRAP"        -> deployResponseTransformationRule(rules, ruleType, analysisIdStr, failureIdStr, actedBy);
                default                       -> deployTransformationRule(rules, ruleType, analysisIdStr, failureIdStr, actedBy);
            }
        } catch (Exception e) {
            log.error("Failed to deploy {} rule: {}", ruleType, e.getMessage(), e);
        }
    }

    // ─── ROUTING_OVERRIDE ────────────────────────────────────────────────────

    private void deployRoutingRule(Map<String, Object> rules, String analysisId,
                                    String failureId, String actedBy) {
        String serviceName   = str(rules.get("serviceName"));
        String originalUrl   = str(rules.get("originalUrl"));
        String newUrl        = str(rules.get("suggestedNewUrl"));
        String discoveryMethod = str(rules.getOrDefault("discoveryMethod", "AI_SUGGESTED"));

        if (RoutingUrlResolver.isBlank(newUrl)) {
            String registered = loadRegisteredBaseUrl(serviceName);
            var resolved = RoutingUrlResolver.resolve(originalUrl, registered, null);
            if (resolved.isPresent()) {
                newUrl = resolved.get().baseUrl();
                discoveryMethod = resolved.get().discoveryMethod();
                rules.put("suggestedNewUrl", newUrl);
                rules.put("discoveryMethod", discoveryMethod);
                log.info("Resolved suggestedNewUrl at deploy time via {}: {}", discoveryMethod, newUrl);
            }
        }

        if (RoutingUrlResolver.isBlank(newUrl)) {
            log.error("Routing rule has no suggestedNewUrl and registry fallback failed — cannot deploy for service '{}'",
                    serviceName);
            return;
        }

        newUrl = RoutingUrlResolver.stripToBaseUrl(newUrl);
        if (!RoutingUrlResolver.isBlank(originalUrl)) {
            originalUrl = RoutingUrlResolver.stripToBaseUrl(originalUrl);
        }

        String registered = loadRegisteredBaseUrl(serviceName);
        if (!RoutingUrlResolver.isBlank(registered) && !RoutingUrlResolver.isBlank(newUrl)) {
            newUrl = RoutingUrlResolver.mergeHostFromAttemptedPortFromRegistry(newUrl, registered);
            log.info("Normalized routing URL against registry for '{}': {}", serviceName, newUrl);
        }

        // Deactivate any existing active routing rule for this service
        jdbcTemplate.update(
            "UPDATE routing_rules SET is_active = false, updated_at = NOW() WHERE service_name = ? AND is_active = true",
            serviceName);

        String ruleId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(defaultTtlHours);

        jdbcTemplate.update("""
            INSERT INTO routing_rules
                (id, service_name, original_url, new_url, discovery_method,
                 failure_id, analysis_id, approved_by, approved_at, is_active, expires_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?::uuid, ?::uuid, ?, NOW(), true, ?)
            """,
            ruleId, serviceName, originalUrl, newUrl, discoveryMethod,
            failureId, analysisId, actedBy, expiresAt);

        // Update services registry
        jdbcTemplate.update(
            "UPDATE services SET base_url = ?, updated_at = NOW() WHERE name = ?",
            newUrl, serviceName);

        // Update route in Redis
        String cacheKey = "route:" + serviceName;
        redisTemplate.opsForValue().set(cacheKey, newUrl, defaultTtlHours, TimeUnit.HOURS);

        // Update failure status
        jdbcTemplate.update("UPDATE api_failures SET status = 'RESOLVED' WHERE id = ?::uuid", failureId);

        audit(ruleId, "ROUTING_RULE", "DEPLOYED", actedBy,
            String.format("{\"service\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"ttlHours\":%d}",
                serviceName, originalUrl, newUrl, defaultTtlHours));

        log.info("✓ Routing rule deployed: {} → {} → {} (TTL {}h)", ruleId, serviceName, newUrl, defaultTtlHours);
        routeChangedPublisher.publishTargetService(serviceName);
    }

    // ─── CORS_ALLOW ──────────────────────────────────────────────────────────

    private void deployCorsRule(Map<String, Object> rules, String analysisId,
                                 String failureId, String actedBy) {
        String targetService   = str(rules.get("targetService"));
        String newOrigin       = str(rules.get("newOrigin"));
        String previousOrigin  = str(rules.get("previousOrigin"));
        String allowedMethods  = str(rules.getOrDefault("allowedMethods", "GET,POST,PUT,DELETE,PATCH,OPTIONS"));
        String allowedHeaders  = str(rules.getOrDefault("allowedHeaders", "*"));

        if (newOrigin == null || newOrigin.isBlank()) {
            log.warn("CORS rule has no newOrigin — cannot deploy");
            return;
        }

        // Deactivate duplicate
        jdbcTemplate.update(
            "UPDATE cors_rules SET is_active = false, updated_at = NOW() WHERE target_service = ? AND allowed_origin = ? AND is_active = true",
            targetService, newOrigin);

        String ruleId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(defaultTtlHours);

        jdbcTemplate.update("""
            INSERT INTO cors_rules
                (id, target_service, allowed_origin, previous_origin, failure_id, analysis_id,
                 allowed_methods, allowed_headers, approved_by, approved_at, is_active, expires_at)
            VALUES (?::uuid, ?, ?, ?, ?::uuid, ?::uuid, ?, ?, ?, NOW(), true, ?)
            """,
            ruleId, targetService, newOrigin, previousOrigin,
            failureId, analysisId, allowedMethods, allowedHeaders, actedBy, expiresAt);

        // Warm Redis
        String cacheKey = "cors:" + targetService + ":" + newOrigin;
        redisTemplate.opsForValue().set(cacheKey, true, defaultTtlHours, TimeUnit.HOURS);

        // Update failure status
        jdbcTemplate.update("UPDATE api_failures SET status = 'RESOLVED' WHERE id = ?::uuid", failureId);

        audit(ruleId, "CORS_RULE", "DEPLOYED", actedBy,
            String.format("{\"targetService\":\"%s\",\"origin\":\"%s\",\"ttlHours\":%d}",
                targetService, newOrigin, defaultTtlHours));

        log.info("✓ CORS rule deployed: {} can now call {} (TTL {}h)", newOrigin, targetService, defaultTtlHours);
        routeChangedPublisher.publishTargetService(targetService);
    }

    // ─── CORS_ORIGIN_OVERRIDE ────────────────────────────────────────────────

    private void deployOriginOverrideRule(Map<String, Object> rules, String analysisId,
                                           String failureId, String actedBy) {
        String sourceService = str(rules.get("sourceService"));
        String targetService = str(rules.get("targetService"));
        String endpoint = normalizeEndpoint(str(rules.get("endpoint")));
        String callerOrigin = str(rules.get("callerOrigin"));
        String outboundOrigin = str(rules.get("outboundOrigin"));
        boolean rewriteResponseAcao = !Boolean.FALSE.equals(rules.get("rewriteResponseAcao"));

        if (isBlank(sourceService) || isBlank(targetService) || isBlank(endpoint)
                || isBlank(callerOrigin) || isBlank(outboundOrigin)) {
            Object[] failureData = loadFailureRoute(failureId);
            if (failureData != null) {
                if (isBlank(sourceService)) sourceService = (String) failureData[0];
                if (isBlank(targetService)) targetService = (String) failureData[1];
                if (isBlank(endpoint)) endpoint = normalizeEndpoint((String) failureData[2]);
            }
        }

        if (isBlank(sourceService) || isBlank(targetService) || isBlank(endpoint)
                || isBlank(callerOrigin) || isBlank(outboundOrigin)) {
            log.warn("Origin override rule missing required fields — cannot deploy");
            return;
        }

        if (endpoint.contains(" ") || looksLikeHttpMethodPrefix(endpoint)) {
            log.warn("Origin override endpoint must be path-only — rejecting: {}", endpoint);
            return;
        }
        if (callerOrigin.equalsIgnoreCase(outboundOrigin)) {
            log.warn("Origin override callerOrigin and outboundOrigin must differ — rejecting");
            return;
        }

        String failureRequestOrigin = loadFailureRequestOrigin(failureId);
        if (!isBlank(failureRequestOrigin) && !callerOrigin.equalsIgnoreCase(failureRequestOrigin.trim())) {
            log.warn("Origin override callerOrigin '{}' does not match failure requestOrigin '{}' — rejecting",
                    callerOrigin, failureRequestOrigin);
            return;
        }

        rules.put("endpoint", endpoint);

        int ttlHours = defaultTtlHours;
        if (rules.get("ttlHours") instanceof Number n) {
            ttlHours = n.intValue();
        }

        jdbcTemplate.update("""
            UPDATE origin_override_rules SET is_active = false, updated_at = NOW()
            WHERE source_service = ? AND target_service = ? AND endpoint = ? AND caller_origin = ? AND is_active = true
            """, sourceService, targetService, endpoint, callerOrigin);

        String ruleId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(ttlHours);

        jdbcTemplate.update("""
            INSERT INTO origin_override_rules
                (id, source_service, target_service, endpoint, caller_origin, outbound_origin,
                 rewrite_response_acao, failure_id, analysis_id, approved_by, approved_at, is_active, expires_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, ?, NOW(), true, ?)
            """,
            ruleId, sourceService, targetService, endpoint, callerOrigin, outboundOrigin,
            rewriteResponseAcao, failureId, analysisId, actedBy, expiresAt);

        jdbcTemplate.update("UPDATE api_failures SET status = 'RESOLVED' WHERE id = ?::uuid", failureId);

        audit(ruleId, "ORIGIN_OVERRIDE_RULE", "DEPLOYED", actedBy,
            String.format("{\"sourceService\":\"%s\",\"targetService\":\"%s\",\"endpoint\":\"%s\","
                    + "\"callerOrigin\":\"%s\",\"outboundOrigin\":\"%s\",\"ttlHours\":%d}",
                sourceService, targetService, endpoint, callerOrigin, outboundOrigin, ttlHours));

        log.info("✓ Origin override deployed: {} → {} rewrites Origin {} → {} on {} (TTL {}h)",
                sourceService, targetService, callerOrigin, outboundOrigin, endpoint, ttlHours);
        routeChangedPublisher.publishRoute(sourceService, targetService, endpoint);
    }

    private Object[] loadFailureRoute(String failureId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT service_a, service_b, endpoint FROM api_failures WHERE id = ?::uuid",
                (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                failureId);
        } catch (Exception e) {
            return null;
        }
    }

    private String loadFailureRequestOrigin(String failureId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT request_origin FROM api_failures WHERE id = ?::uuid",
                String.class,
                failureId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeEndpoint(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String s = raw.trim();
        int space = s.indexOf(' ');
        if (space > 0) {
            String token = s.substring(0, space).toUpperCase();
            if (looksLikeHttpMethodPrefix(token)) {
                s = s.substring(space + 1).trim();
            }
        }
        return s.startsWith("/") ? s : "/" + s;
    }

    private static boolean looksLikeHttpMethodPrefix(String token) {
        return switch (token.toUpperCase()) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD" -> true;
            default -> false;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ─── RESPONSE TRANSFORMATION ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void deployResponseTransformationRule(Map<String, Object> rules, String ruleType,
                                                   String analysisId, String failureId, String actedBy) throws Exception {
        Object[] failureData = jdbcTemplate.queryForObject(
            "SELECT service_a, service_b, endpoint FROM api_failures WHERE id = ?::uuid",
            (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)},
            failureId);

        if (failureData == null) { log.error("Failure not found: {}", failureId); return; }

        String serviceA  = (String) failureData[0];
        String serviceB  = (String) failureData[1];
        String endpoint  = (String) failureData[2];
        String rulesJson = objectMapper.writeValueAsString(rules);
        java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now().plusHours(defaultTtlHours);
        String ruleId    = java.util.UUID.randomUUID().toString();

        jdbcTemplate.update("""
            INSERT INTO response_transformation_rules
                (id, analysis_id, service_a, service_b, endpoint, rule_type, rule_definition,
                 description, approved_by, approved_at, expires_at, is_active, version)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?::jsonb, ?, ?, NOW(), ?, true, 1)
            """,
            ruleId, analysisId, serviceA, serviceB, endpoint,
            ruleType, rulesJson,
            "AI-generated " + ruleType + " response rule approved by " + actedBy,
            actedBy, expiresAt);

        jdbcTemplate.update("UPDATE api_failures SET status = 'RESOLVED' WHERE id = ?::uuid", failureId);

        // Evict response rule cache in gateway
        String cacheKey = "resp_rules:" + serviceA + ":" + serviceB + ":" + endpoint;
        redisTemplate.delete(cacheKey);

        audit(ruleId, "RESPONSE_TRANSFORMATION_RULE", "DEPLOYED", actedBy,
            String.format("{\"type\":\"%s\",\"analysisId\":\"%s\",\"ttlHours\":%d}",
                ruleType, analysisId, defaultTtlHours));

        log.info("✓ Response transformation rule deployed: {} type={} for {}→{}{} (TTL {}h)",
            ruleId, ruleType, serviceA, serviceB, endpoint, defaultTtlHours);
        routeChangedPublisher.publishRoute(serviceA, serviceB, endpoint);
    }

    @SuppressWarnings("unchecked")
    private void deployTransformationRule(Map<String, Object> rules, String ruleType,
                                           String analysisId, String failureId, String actedBy) throws Exception {
        Object[] failureData = jdbcTemplate.queryForObject(
            "SELECT service_a, service_b, endpoint FROM api_failures WHERE id = ?::uuid",
            (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)},
            failureId);

        if (failureData == null) { log.error("Failure not found: {}", failureId); return; }

        String serviceA = (String) failureData[0];
        String serviceB = (String) failureData[1];
        String endpoint = (String) failureData[2];
        String rulesJson = objectMapper.writeValueAsString(rules);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(defaultTtlHours);

        String ruleId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO transformation_rules
                (id, analysis_id, service_a, service_b, endpoint, rule_type, rule_definition,
                 description, approved_by, approved_at, expires_at, is_active, version)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?::jsonb, ?, ?, NOW(), ?, true, 1)
            """,
            ruleId, analysisId, serviceA, serviceB, endpoint,
            ruleType, rulesJson,
            "AI-generated " + ruleType + " rule approved by " + actedBy,
            actedBy, expiresAt);

        jdbcTemplate.update("UPDATE api_failures SET status = 'RESOLVED' WHERE id = ?::uuid", failureId);

        // Evict gateway rule cache
        String cacheKey = "rules:" + serviceA + ":" + serviceB + ":" + endpoint;
        redisTemplate.delete(cacheKey);

        audit(ruleId, "TRANSFORMATION_RULE", "DEPLOYED", actedBy,
            String.format("{\"type\":\"%s\",\"analysisId\":\"%s\",\"ttlHours\":%d}",
                ruleType, analysisId, defaultTtlHours));

        log.info("✓ Transformation rule deployed: {} type={} for {}→{}{} (TTL {}h)",
            ruleId, ruleType, serviceA, serviceB, endpoint, defaultTtlHours);
        routeChangedPublisher.publishRoute(serviceA, serviceB, endpoint);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void audit(String entityId, String entityType, String action, String actor, String detailsJson) {
        try {
            jdbcTemplate.update("""
                INSERT INTO audit_log (entity_type, entity_id, action, actor, details)
                VALUES (?, ?::uuid, ?, ?, ?::jsonb)
                """, entityType, entityId, action, actor, detailsJson);
        } catch (Exception e) {
            log.debug("Audit log write failed: {}", e.getMessage());
        }
    }

    private String loadRegisteredBaseUrl(String serviceName) {
        if (serviceName == null) return null;
        try {
            return jdbcTemplate.query(
                    "SELECT base_url FROM services WHERE name = ? AND is_active = true AND base_url IS NOT NULL LIMIT 1",
                    rs -> rs.next() ? rs.getString("base_url") : null,
                    serviceName);
        } catch (Exception e) {
            log.debug("Could not load registry URL for {}: {}", serviceName, e.getMessage());
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String normalizeRuleType(String raw) {
        if (raw == null || raw.isBlank()) return "FIELD_RENAME";
        String upper = raw.toUpperCase().trim();
        if (upper.contains("|") || upper.contains(",")) return "NESTED_TRANSFORM";
        return upper;
    }
}
