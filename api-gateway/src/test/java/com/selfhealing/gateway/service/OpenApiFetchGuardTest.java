package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.OpenApiSpecRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenApiFetchGuardTest {

    @Mock private ServiceRegistryService registryService;
    @Mock private OpenApiSpecRegistryRepository specRegistryRepository;

    private OpenApiFetchGuard guard;

    @BeforeEach
    void setUp() {
        guard = new OpenApiFetchGuard(registryService, specRegistryRepository);
    }

    @Test
    void allowsUrlUnderRegisteredServiceBase() {
        when(registryService.getAllServices()).thenReturn(List.of(
                ServiceRegistration.builder()
                        .name("payment-service")
                        .baseUrl("http://payment-service:8091")
                        .isActive(true)
                        .build()));

        assertThatCode(() -> guard.assertAllowed("http://payment-service:8091/v3/api-docs"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertAllowed("http://payment-service:8091/openapi.json"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnregisteredHost() {
        when(registryService.getAllServices()).thenReturn(List.of(
                ServiceRegistration.builder()
                        .name("payment-service")
                        .baseUrl("http://payment-service:8091")
                        .isActive(true)
                        .build()));
        when(specRegistryRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> guard.assertAllowed("http://evil.example.com/openapi.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not under any registered service baseUrl");
    }

    @Test
    void rejectsMetadataHostEvenIfSomehowRegistered() {
        assertThatThrownBy(() -> guard.assertAllowed("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> guard.assertAllowed("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http(s)");
    }
}
