package com.selfhealing.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class MendrOtlpEnvironmentPostProcessorTest {

    private final MendrOtlpEnvironmentPostProcessor processor = new MendrOtlpEnvironmentPostProcessor();

    @Test
    void disablesOtlpWhenFlagOff() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(MendrOtlpEnvironmentPostProcessor.ENV_ENABLED, "false");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.tracing.enabled")).isEqualTo("false");
        assertThat(env.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.autoconfigure.exclude"))
                .contains(MendrOtlpEnvironmentPostProcessor.OTLP_AUTO_CONFIG);
    }

    @Test
    void disablesOtlpWhenEnabledWithoutEndpoint() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(MendrOtlpEnvironmentPostProcessor.ENV_ENABLED, "true");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.tracing.enabled")).isEqualTo("false");
        assertThat(env.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.autoconfigure.exclude"))
                .contains(MendrOtlpEnvironmentPostProcessor.OTLP_AUTO_CONFIG);
    }

    @Test
    void enablesOtlpWhenEnabledWithValidEndpoint() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(MendrOtlpEnvironmentPostProcessor.ENV_ENABLED, "true");
        env.setProperty(MendrOtlpEnvironmentPostProcessor.ENV_ENDPOINT,
                "http://otel-collector:4318/v1/traces");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.tracing.enabled")).isEqualTo("true");
        assertThat(env.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("true");
        assertThat(env.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://otel-collector:4318/v1/traces");
        assertThat(env.getProperty("spring.autoconfigure.exclude")).isNull();
    }

    @Test
    void rejectsBlankEndpointEvenWhenEnabled() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(MendrOtlpEnvironmentPostProcessor.ENV_ENABLED, "true");
        env.setProperty(MendrOtlpEnvironmentPostProcessor.ENV_ENDPOINT, "   ");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.tracing.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.autoconfigure.exclude"))
                .contains(MendrOtlpEnvironmentPostProcessor.OTLP_AUTO_CONFIG);
    }
}
