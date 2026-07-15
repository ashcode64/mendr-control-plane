package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngressHostIdentityServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private IngressHostIdentityService service;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new IngressHostIdentityService(redis, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void register_writesTenantScopedKey() {
        service.register("API.Acme.COM", "order-service");

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCap.capture(), org.mockito.ArgumentMatchers.anyString());
        assertThat(keyCap.getValue()).isEqualTo("t:" + tenantId + ":mendr:hostident:api.acme.com");
    }

    @Test
    void collectAll_onlyCurrentTenant_andStripsNamespace() {
        String mine = "t:" + tenantId + ":mendr:hostident:api.acme.com";
        when(redis.keys("t:" + tenantId + ":mendr:hostident:*"))
                .thenReturn(Set.of(mine));
        when(valueOps.get(mine)).thenReturn("{\"sourceService\":\"order-service\"}");

        Map<String, String> out = service.collectAll();

        assertThat(out).containsOnlyKeys("mendr:hostident:api.acme.com");
        assertThat(out.get("mendr:hostident:api.acme.com")).contains("order-service");
    }
}
