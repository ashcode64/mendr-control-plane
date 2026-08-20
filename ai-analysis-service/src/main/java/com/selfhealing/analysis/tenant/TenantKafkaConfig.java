package com.selfhealing.analysis.tenant;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant propagation across Kafka:
 * <ul>
 *   <li>producer interceptor stamps the {@code tenant_id} header from
 *       {@link TenantContext} on every outbound record;</li>
 *   <li>the listener container factory binds {@link TenantContext} from that
 *       header before each listener invocation (and clears it after).</li>
 * </ul>
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

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setRecordInterceptor(new TenantRecordInterceptor());
        // Cap LLM fan-out: one consumer thread; long poll interval for slow diagnose/LLM.
        factory.setConcurrency(1);
        return factory;
    }
}
