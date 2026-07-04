package com.selfhealing.notification.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The notification service is Kafka-only: it consumes analysis-result events and has no
 * intended HTTP API. It nonetheless runs an embedded servlet container (web starter), so we
 * fail closed — every HTTP request is denied except the framework error dispatch. This makes
 * the service safe by default and forces any future endpoint to opt into an explicit auth
 * rule rather than being silently exposed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
