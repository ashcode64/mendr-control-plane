package com.selfhealing.rules.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Inbound auth settings for the rule-engine HTTP surface ({@code /api/rules}). Human
 * dashboard auth is delegated to WorkOS (JWT validated via JWKS); trusted service-to-service
 * callers use the shared internal key. Mirrors the gateway's {@code AuthProperties} so every
 * service is configured the same way.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mendr.auth")
public class AuthProperties {

    /**
     * When true, {@code /api/rules} requires a valid credential (WorkOS JWT or internal key).
     * When false (default) endpoints stay open but any credential still binds the tenant — a
     * safe incremental rollout.
     */
    private boolean enforce = false;

    /** Shared internal key (same value as the gateway's GATEWAY_INTERNAL_API_KEY). */
    private String internalApiKey = "";

    /** Allowed browser origins for {@code /api/rules} (never {@code *}). */
    private List<String> corsAllowedOrigins = List.of("http://localhost:3000");

    private final Workos workos = new Workos();

    @Data
    public static class Workos {
        /** JWKS endpoint of the WorkOS environment. Empty disables JWT validation. */
        private String jwksUri = "";
        private String issuer = "";
        private String audience = "";
        /** JWT claim that carries the WorkOS organization id. */
        private String orgClaim = "org_id";
    }
}
