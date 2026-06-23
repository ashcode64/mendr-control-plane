package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Post-parse validation for deployable transformation rules.
 */
public final class RuleValidator {

    public record ValidationResult(boolean deployable, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    private RuleValidator() {}

    public static ValidationResult validate(
            Map<String, Object> rules,
            ApiFailureEvent event,
            List<String> upstreamAllowedOrigins) {

        if (rules == null || rules.isEmpty()) {
            return ValidationResult.fail("empty transformation rules");
        }

        String type = str(rules.get("type")).toUpperCase();
        return switch (type) {
            case "CORS_ORIGIN_OVERRIDE" -> validateOriginOverride(rules, event, upstreamAllowedOrigins);
            case "CORS_ALLOW" -> validateCorsAllow(rules, event);
            case "ROUTING_OVERRIDE" -> validateRouting(rules);
            case "TYPE_COERCE" -> validateTypeCoerce(rules);
            case "ADD_DEFAULT" -> validateAddDefault(rules);
            case "FIELD_RENAME" -> validateFieldRename(rules);
            default -> ValidationResult.ok();
        };
    }

    private static ValidationResult validateOriginOverride(
            Map<String, Object> rules,
            ApiFailureEvent event,
            List<String> upstreamAllowedOrigins) {

        String caller = str(rules.get("callerOrigin"));
        String outbound = str(rules.get("outboundOrigin"));
        String endpoint = EndpointNormalizer.normalize(str(rules.get("endpoint")));

        if (caller.isBlank() || outbound.isBlank()) {
            return ValidationResult.fail("callerOrigin and outboundOrigin are required");
        }
        if (endpoint.isBlank() || endpoint.contains(" ")) {
            return ValidationResult.fail("endpoint must be a path only (no HTTP method prefix)");
        }
        if (caller.equalsIgnoreCase(outbound)) {
            return ValidationResult.fail("callerOrigin and outboundOrigin must differ");
        }

        String requestOrigin = event.getRequestOrigin();
        if (requestOrigin != null && !requestOrigin.isBlank()
                && !caller.equalsIgnoreCase(requestOrigin.trim())) {
            return ValidationResult.fail("callerOrigin must match failure requestOrigin");
        }

        if (looksLikeServiceBaseUrl(caller, event.getRegisteredBaseUrl(), event.getTargetServiceUrl())) {
            return ValidationResult.fail("callerOrigin must not be a service base URL");
        }
        if (looksLikeServiceBaseUrl(caller, event.getRegisteredBaseUrl(), null)
                && requestOrigin != null && !caller.equalsIgnoreCase(requestOrigin)) {
            return ValidationResult.fail("callerOrigin appears to be registeredBaseUrl, not caller Origin");
        }

        if (!upstreamAllowedOrigins.isEmpty()) {
            Set<String> allowed = upstreamAllowedOrigins.stream()
                    .map(String::trim)
                    .collect(Collectors.toSet());
            if (!allowed.contains(outbound)) {
                return ValidationResult.fail("outboundOrigin must be in upstreamAllowedOrigins");
            }
        }

        if (str(rules.get("targetService")).isBlank() || str(rules.get("sourceService")).isBlank()) {
            return ValidationResult.fail("sourceService and targetService are required");
        }

        return ValidationResult.ok();
    }

    private static ValidationResult validateCorsAllow(Map<String, Object> rules, ApiFailureEvent event) {
        String newOrigin = str(rules.get("newOrigin"));
        if (newOrigin.isBlank()) {
            return ValidationResult.fail("newOrigin is required for CORS_ALLOW");
        }
        String requestOrigin = event.getRequestOrigin();
        if (requestOrigin != null && !requestOrigin.isBlank()
                && !newOrigin.equalsIgnoreCase(requestOrigin.trim())) {
            return ValidationResult.fail("newOrigin must match blocked requestOrigin");
        }
        if (looksLikeServiceBaseUrl(newOrigin, event.getRegisteredBaseUrl(), event.getTargetServiceUrl())) {
            return ValidationResult.fail("newOrigin must not be a service base URL");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateRouting(Map<String, Object> rules) {
        if (RoutingUrlResolver.isBlank(str(rules.get("suggestedNewUrl")))) {
            return ValidationResult.fail("suggestedNewUrl is required for ROUTING_OVERRIDE");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateTypeCoerce(Map<String, Object> rules) {
        if (!(rules.get("coercions") instanceof Map<?, ?> m) || m.isEmpty()) {
            return ValidationResult.fail("coercions map is required for TYPE_COERCE");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateAddDefault(Map<String, Object> rules) {
        if (!(rules.get("defaults") instanceof Map<?, ?> m) || m.isEmpty()) {
            return ValidationResult.fail("defaults map is required for ADD_DEFAULT");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateFieldRename(Map<String, Object> rules) {
        if (!(rules.get("mappings") instanceof Map<?, ?> m) || m.isEmpty()) {
            return ValidationResult.fail("mappings map is required for FIELD_RENAME");
        }
        return ValidationResult.ok();
    }

    static boolean looksLikeServiceBaseUrl(String origin, String registeredBaseUrl, String targetServiceUrl) {
        if (origin == null || origin.isBlank()) return false;
        String normalizedOrigin = normalizeOrigin(origin);
        if (registeredBaseUrl != null && normalizedOrigin.equals(normalizeOrigin(registeredBaseUrl))) {
            return true;
        }
        if (targetServiceUrl != null && normalizedOrigin.equals(normalizeOrigin(targetServiceUrl))) {
            return true;
        }
        return false;
    }

    static String normalizeOrigin(String urlOrOrigin) {
        if (urlOrOrigin == null || urlOrOrigin.isBlank()) return "";
        try {
            URI uri = URI.create(urlOrOrigin.trim());
            if (uri.getScheme() == null) return urlOrOrigin.trim();
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + ":" + port;
        } catch (Exception e) {
            return urlOrOrigin.trim();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
