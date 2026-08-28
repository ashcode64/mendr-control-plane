package com.selfhealing.gateway.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps api-gateway bootable when OTLP tracing is off or misconfigured.
 *
 * <p>Spring Boot's OTLP auto-config may instantiate {@code OtlpHttpSpanExporter} with an
 * empty {@code management.otlp.tracing.endpoint}, which fails startup. We only enable
 * OTLP when {@code MENDR_OTEL_ENABLED=true} <em>and</em> a valid http(s) endpoint is set;
 * otherwise tracing export stays off and OTLP auto-config is excluded.
 */
public class MendrOtlpEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String ENV_ENABLED = "MENDR_OTEL_ENABLED";
    static final String ENV_ENDPOINT = "MENDR_OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String OTLP_AUTO_CONFIG =
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration";
    static final String AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean enabled = parseBoolean(environment.getProperty(ENV_ENABLED, "false"));
        String endpoint = normalize(environment.getProperty(ENV_ENDPOINT));
        boolean otlpReady = enabled && isValidHttpEndpoint(endpoint);

        Map<String, Object> props = new HashMap<>();
        if (otlpReady) {
            props.put("management.tracing.enabled", "true");
            props.put("management.otlp.tracing.export.enabled", "true");
            props.put("management.otlp.tracing.endpoint", endpoint);
        } else {
            props.put("management.tracing.enabled", "false");
            props.put("management.otlp.tracing.export.enabled", "false");
            appendAutoConfigureExclude(environment, props, OTLP_AUTO_CONFIG);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("mendrOtlpGuard", props));
    }

    static boolean isValidHttpEndpoint(String endpoint) {
        return endpoint != null
                && !endpoint.isBlank()
                && (endpoint.startsWith("http://") || endpoint.startsWith("https://"));
    }

    static boolean parseBoolean(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase()) {
            case "true", "1", "yes", "on" -> true;
            default -> false;
        };
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static void appendAutoConfigureExclude(
            ConfigurableEnvironment environment, Map<String, Object> props, String autoConfigClass) {
        String existing = environment.getProperty(AUTOCONFIGURE_EXCLUDE, "");
        if (existing.contains(autoConfigClass)) {
            return;
        }
        props.put(AUTOCONFIGURE_EXCLUDE,
                existing.isBlank() ? autoConfigClass : existing + "," + autoConfigClass);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
