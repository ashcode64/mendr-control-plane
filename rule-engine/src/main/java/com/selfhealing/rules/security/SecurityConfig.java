package com.selfhealing.rules.security;

import com.selfhealing.rules.config.AuthProperties;
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
 * Inbound auth for the rule-engine HTTP surface. Same stack as the gateway and ai-analysis:
 *
 * <ul>
 *   <li>Stateless; CSRF disabled; CORS from the allow-list.</li>
 *   <li>Human (dashboard): WorkOS JWT validated via JWKS (when configured).</li>
 *   <li>Machine: shared internal key ({@link InternalApiKeyAuthFilter}).</li>
 *   <li>Tenant bound after authorization by {@link TenantContextFilter}.</li>
 *   <li>{@code mendr.auth.enforce=false} keeps endpoints open for the safe rollout while
 *       still binding tenant context from any credential.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthProperties properties;
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
                        registry.requestMatchers("/api/rules/**").authenticated();
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
