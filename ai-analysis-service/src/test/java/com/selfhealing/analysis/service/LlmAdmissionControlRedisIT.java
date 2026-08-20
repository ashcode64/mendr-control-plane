package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Redis integration test via Testcontainers. Skipped automatically when
 * Docker is unavailable (offline / CI without a daemon).
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class LlmAdmissionControlRedisIT {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LlmAdmissionControl gate;
    private StringRedisTemplate redis;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        gate = new LlmAdmissionControl(redis, new SimpleMeterRegistry(), 2, 30, 30, 10);
    }

    private static ApiFailureEvent event() {
        return ApiFailureEvent.builder()
                .failureId(UUID.randomUUID())
                .failureCategory("SCHEMA_MISMATCH")
                .endpoint("/api/payments/{id}")
                .build();
    }

    private static ErrorSignature signature(String changeType, String jsonPath) {
        return new ErrorSignature(
                UUID.randomUUID(),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "SCHEMA_MISMATCH",
                "tmpl-payments",
                jsonPath,
                changeType,
                "integer",
                "string",
                "1",
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void identicalSignaturesCoalesceAgainstRealRedis() {
        assertThat(gate.admitSignature(signature("TYPE_COERCE", "/amount")).admitted()).isTrue();

        LlmAdmissionControl.Decision second = gate.admitSignature(signature("TYPE_COERCE", "/amount"));
        assertThat(second.admitted()).isFalse();
        assertThat(second.reason()).isEqualTo("coalesce");
        // Coalesce defer must not touch budget counters.
        assertThat(redis.hasKey("mendr:analyze:global")).isFalse();

        gate.releaseCoalesce();
    }

    @Test
    void distinctSignaturesAdmitIndependently() {
        assertThat(gate.admitSignature(signature("TYPE_COERCE", "/amount")).admitted()).isTrue();
        gate.releaseCoalesce();

        assertThat(gate.admitSignature(signature("RENAME", "/amt")).admitted()).isTrue();
        gate.releaseCoalesce();
    }

    @Test
    void budgetConsumedOnlyOnFullAdmit() {
        gate = new LlmAdmissionControl(redis, new SimpleMeterRegistry(), 2, 30, 2, 2);

        ErrorSignature a = signature("TYPE_COERCE", "/a");
        assertThat(gate.admitSignature(a).admitted()).isTrue();
        assertThat(gate.tryAcquire(a).admitted()).isTrue();
        assertThat(gate.consumeBudget(event()).admitted()).isTrue();
        gate.release();
        gate.clearCoalesceHold();

        ErrorSignature b = signature("TYPE_COERCE", "/b");
        assertThat(gate.admitSignature(b).admitted()).isTrue();
        assertThat(gate.tryAcquire(b).admitted()).isTrue();
        assertThat(gate.consumeBudget(event()).admitted()).isTrue();
        gate.release();
        gate.clearCoalesceHold();

        ErrorSignature c = signature("TYPE_COERCE", "/c");
        assertThat(gate.admitSignature(c).admitted()).isTrue();
        assertThat(gate.tryAcquire(c).admitted()).isTrue();
        LlmAdmissionControl.Decision over = gate.consumeBudget(event());
        assertThat(over.admitted()).isFalse();
        assertThat(over.reason()).isEqualTo("budget");
        // Coalesce for /c released — can be reclaimed.
        assertThat(gate.admitSignature(c).admitted()).isTrue();
        gate.releaseCoalesce();
    }

    @Test
    void semaphoreDeferReleasesCoalesceAgainstRealRedis() {
        gate = new LlmAdmissionControl(redis, new SimpleMeterRegistry(), 1, 30, 100, 100);

        ErrorSignature a = signature("TYPE_COERCE", "/a");
        ErrorSignature b = signature("TYPE_COERCE", "/b");

        assertThat(gate.admitSignature(a).admitted()).isTrue();
        assertThat(gate.tryAcquire(a).admitted()).isTrue();

        assertThat(gate.admitSignature(b).admitted()).isTrue();
        LlmAdmissionControl.Decision d = gate.tryAcquire(b);
        assertThat(d.admitted()).isFalse();
        assertThat(d.reason()).isEqualTo("semaphore");
        assertThat(redis.hasKey("mendr:analyze:global")).isFalse();

        assertThat(gate.admitSignature(b).admitted()).isTrue();
        gate.releaseCoalesce();
        gate.release();
    }
}
