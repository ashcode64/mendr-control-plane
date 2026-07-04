package com.selfhealing.gateway.tenant;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stamps every outbound Kafka record with the current tenant id (header
 * {@code tenant_id}) via a {@link TenantProducerInterceptor}, so downstream
 * consumers can re-establish the tenant context for RLS-scoped DB access.
 */
@Configuration
public class TenantKafkaConfig {

    @Bean
    public DefaultKafkaProducerFactoryCustomizer tenantProducerInterceptorCustomizer() {
        return (DefaultKafkaProducerFactory<?, ?> factory) -> {
            Map<String, Object> configs = new HashMap<>(factory.getConfigurationProperties());
            configs.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                    List.of(TenantProducerInterceptor.class.getName()));
            factory.updateConfigs(configs);
        };
    }
}
