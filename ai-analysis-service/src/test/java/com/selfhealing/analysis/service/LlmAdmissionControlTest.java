package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmAdmissionControlTest {

    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    LlmAdmissionControl gate;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        gate = new LlmAdmissionControl(redis, new SimpleMeterRegistry(), 1, 30, 30, 10);
    }

    private static ApiFailureEvent event() {
        return ApiFailureEvent.builder()
                .failureId(UUID.randomUUID())
                .failureCategory("SCHEMA_MISMATCH")
                .serviceA("order-service")
                .serviceB("payment-service")
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
    void coalesceDeferDoesNotConsumeBudget() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        LlmAdmissionControl.Decision d = gate.admitSignature(signature("TYPE_COERCE", "/amount"));

        assertThat(d.admitted()).isFalse();
        assertThat(d.reason()).isEqualTo("coalesce");
        verify(values, never()).increment(anyString());
    }

    @Test
    void budgetCountedOnlyAfterCoalesceAndSemaphore() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        ErrorSignature sig = signature("TYPE_COERCE", "/amount");
        assertThat(gate.admitSignature(sig).admitted()).isTrue();
        assertThat(gate.tryAcquire(sig).admitted()).isTrue();
        assertThat(gate.consumeBudget(event()).admitted()).isTrue();

        verify(values).increment("mendr:analyze:global");
        gate.release();
        gate.clearCoalesceHold();
    }

    @Test
    void budgetDeferReleasesSemaphoreAndCoalesce() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(values.increment(anyString())).thenReturn(1L);
        when(values.increment("mendr:analyze:global")).thenReturn(31L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        ErrorSignature sig = signature("TYPE_COERCE", "/amount");
        assertThat(gate.admitSignature(sig).admitted()).isTrue();
        assertThat(gate.tryAcquire(sig).admitted()).isTrue();

        LlmAdmissionControl.Decision d = gate.consumeBudget(event());
        assertThat(d.admitted()).isFalse();
        assertThat(d.reason()).isEqualTo("budget");
        verify(redis, atLeastOnce()).delete(anyString());

        // Semaphore was released — a second acquire should succeed.
        ErrorSignature other = signature("RENAME", "/amt");
        assertThat(gate.admitSignature(other).admitted()).isTrue();
        assertThat(gate.tryAcquire(other).admitted()).isTrue();
        gate.release();
        gate.clearCoalesceHold();
    }

    @Test
    void distinctSignaturesDoNotCoalesce() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertThat(gate.admitSignature(signature("TYPE_COERCE", "/amount")).admitted()).isTrue();
        gate.releaseCoalesce();
        assertThat(gate.admitSignature(signature("RENAME", "/amt")).admitted()).isTrue();
        gate.releaseCoalesce();
    }

    @Test
    void identicalSignaturesCoalesce() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true)
                .thenReturn(false);

        assertThat(gate.admitSignature(signature("TYPE_COERCE", "/amount")).admitted()).isTrue();
        LlmAdmissionControl.Decision second = gate.admitSignature(signature("TYPE_COERCE", "/amount"));
        assertThat(second.admitted()).isFalse();
        assertThat(second.reason()).isEqualTo("coalesce");
        gate.releaseCoalesce();
    }

    @Test
    void semaphoreDeferReleasesCoalesceKey() {
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        ErrorSignature first = signature("TYPE_COERCE", "/a");
        ErrorSignature second = signature("TYPE_COERCE", "/b");

        assertThat(gate.admitSignature(first).admitted()).isTrue();
        assertThat(gate.tryAcquire(first).admitted()).isTrue();

        assertThat(gate.admitSignature(second).admitted()).isTrue();
        LlmAdmissionControl.Decision d = gate.tryAcquire(second);
        assertThat(d.admitted()).isFalse();
        assertThat(d.reason()).isEqualTo("semaphore");
        verify(redis, atLeastOnce()).delete(anyString());

        gate.release();
    }
}
