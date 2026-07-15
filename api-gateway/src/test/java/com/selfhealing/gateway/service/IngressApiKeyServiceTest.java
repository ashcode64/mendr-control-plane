package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.model.ApiKey;
import com.selfhealing.gateway.security.ApiKeyService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngressApiKeyServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ApiKeyService apiKeyService;
    @Mock private RouteConfigSnapshotPublisher snapshotPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IngressApiKeyService service;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new IngressApiKeyService(redis, objectMapper, apiKeyService, snapshotPublisher);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void issue_usesApiKeyServiceFormat_andPublishesPrefixKeyedProjection() throws Exception {
        ApiKey stored = ApiKey.builder()
                .tenantId(tenantId)
                .keyPrefix("mendr_AbC123xyz")
                .keyHash(ApiKeyService.sha256Hex("the-secret-value-here-ok"))
                .name("ingress:order-service")
                .build();
        when(apiKeyService.issue(eq(tenantId), eq("ingress:order-service"), isNull(), isNull()))
                .thenReturn(new ApiKeyService.IssuedKey(stored, "mendr_AbC123xyz.the-secret-value-here-ok"));

        Map<String, Object> out = service.issue("order-service");

        assertThat(out.get("apiKey")).isEqualTo("mendr_AbC123xyz.the-secret-value-here-ok");
        assertThat(out.get("keyPrefix")).isEqualTo("mendr_AbC123xyz");
        assertThat(out.get("sourceService")).isEqualTo("order-service");

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCap.capture(), valCap.capture());
        assertThat(keyCap.getValue()).isEqualTo("t:" + tenantId + ":mendr:apikey:mendr_AbC123xyz");
        assertThat(valCap.getValue()).contains("\"keyHash\"");
        assertThat(valCap.getValue()).contains("order-service");
        assertThat(valCap.getValue()).doesNotContain("the-secret-value-here-ok");
        verify(snapshotPublisher).bumpSyncVersionAndNotify();
    }

    @Test
    void register_rejectsOpaqueWholeKey_requiresPrefixDotSecret() {
        assertThatThrownBy(() -> service.register("abcdefghijklmnopqrstuvwxyz012345", "svc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<prefix>.<secret>");
    }

    @Test
    void register_writesSha256OfSecretOnly() {
        String secret = "abcdefghijklmnopqrstuvwx";
        String plaintext = "mendr_prefix01." + secret;
        service.register(plaintext, "billing");

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCap.capture(), valCap.capture());
        assertThat(keyCap.getValue()).isEqualTo("t:" + tenantId + ":mendr:apikey:mendr_prefix01");
        assertThat(valCap.getValue()).contains(ApiKeyService.sha256Hex(secret));
        assertThat(valCap.getValue()).doesNotContain(ApiKeyService.sha256Hex(plaintext));
        verify(snapshotPublisher).bumpSyncVersionAndNotify();
    }
}
