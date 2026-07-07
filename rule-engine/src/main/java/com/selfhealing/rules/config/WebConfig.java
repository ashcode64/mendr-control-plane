package com.selfhealing.rules.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS for the rule-engine API, restricted to the configured dashboard origins (never
 * {@code *}). Exposed as a {@link CorsConfigurationSource} bean so the Spring Security filter
 * chain honours it (including preflight). Replaces the former {@code @CrossOrigin(origins = "*")}.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig {

    private final AuthProperties authProperties;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(authProperties.getCorsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/rules/**", config);
        return source;
    }
}
