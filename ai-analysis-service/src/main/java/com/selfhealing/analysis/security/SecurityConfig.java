package com.selfhealing.analysis.security;

import com.selfhealing.analysis.config.AnalysisSecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Inbound auth for the ai-analysis HTTP surface. Mirrors the gateway's stack so every
 * service vets its callers the same way:
 *
 * <ul>
 *   <li>Stateless; CSRF disabled (API + machine traffic); CORS from the MVC allow-list.</li>
 *   <li>Human (dashboard): WorkOS JWT validated via JWKS (when configured).</li>
 *   <li>Machine (conversation engine / services): shared internal key
 *       ({@link InternalApiKeyAuthFilter}).</li>
 *   <li>Tenant bound after authorization by {@link TenantContextFilter}.</li>
 *   <li>{@code mendr.analysis.security.enforce=false} keeps endpoints open for the safe
 *       incremental rollout while still binding tenant context from any credential.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AnalysisSecurityProperties properties;
    private final TenantResolver tenantResolver;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> {
                    registry.requestMatchers("/actuator/**", "/error").permitAll();
                    if (properties.isEnforce()) {
                        // /mcp and /api/internal/analysis are machine-only; /api/analysis is
                        // dashboard (JWT) + internal.
                        registry.requestMatchers("/mcp/**", "/api/analysis/**", "/api/internal/analysis/**")
                                .authenticated();
                        registry.anyRequest().permitAll();
                    } else {
                        registry.anyRequest().permitAll();
                    }
                });

        String jwksUri = properties.getWorkos().getJwksUri();
        if (jwksUri != null && !jwksUri.isBlank()) {
            JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
            http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)));
        }

        http.addFilterBefore(new InternalApiKeyAuthFilter(properties), AuthorizationFilter.class);
        http.addFilterAfter(new TenantContextFilter(properties, tenantResolver), AuthorizationFilter.class);

        return http.build();
    }
}
