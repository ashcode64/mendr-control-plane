package com.selfhealing.gateway.security;

import com.selfhealing.gateway.config.AuthProperties;
import com.selfhealing.gateway.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Security wiring for the control plane.
 *
 * <ul>
 *   <li>Stateless; no sessions, no CSRF (API + machine traffic).</li>
 *   <li>API keys authenticated before authorization (machine/edge).</li>
 *   <li>WorkOS JWTs validated when a decoder is configured (human dashboard).</li>
 *   <li>Tenant context bound after authorization, around controller execution.</li>
 *   <li>{@code mendr.auth.enforce=false} keeps endpoints open for incremental
 *       rollout while still populating tenant context from any credentials.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthProperties authProperties;
    private final ApiKeyService apiKeyService;
    private final TenantRepository tenantRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> {
                    registry.requestMatchers("/actuator/**", "/health").permitAll();
                    if (authProperties.isEnforce()) {
                        registry.anyRequest().authenticated();
                    } else {
                        registry.anyRequest().permitAll();
                    }
                });

        String jwksUri = authProperties.getWorkos().getJwksUri();
        if (jwksUri != null && !jwksUri.isBlank()) {
            JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
            http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)));
        }

        http.addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService), AuthorizationFilter.class);
        http.addFilterAfter(new TenantContextFilter(tenantRepository, authProperties), AuthorizationFilter.class);

        return http.build();
    }
}
