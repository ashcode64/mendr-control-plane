package com.selfhealing.gateway.security;

import com.selfhealing.gateway.model.ApiKey;
import com.selfhealing.gateway.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock private ApiKeyRepository repository;

    @Test
    void issue_thenAuthenticate_roundTrips_andNeverStoresRawSecret() {
        when(repository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
        ApiKeyService service = new ApiKeyService(repository);
        UUID tenantId = UUID.randomUUID();

        ApiKeyService.IssuedKey issued = service.issue(tenantId, "edge-key", null, null);

        // Plaintext is <prefix>.<secret>; only the sha256 hash is persisted.
        assertThat(issued.plaintext()).contains(".");
        assertThat(issued.stored().getKeyHash()).isNotBlank();
        String secret = issued.plaintext().substring(issued.plaintext().lastIndexOf('.') + 1);
        assertThat(issued.stored().getKeyHash()).isNotEqualTo(secret);
        assertThat(issued.stored().getTenantId()).isEqualTo(tenantId);

        // The stored row authenticates the presented plaintext back to its tenant.
        when(repository.findByKeyPrefix(issued.stored().getKeyPrefix()))
                .thenReturn(Optional.of(issued.stored()));
        Optional<ApiKey> authed = service.authenticate(issued.plaintext());
        assertThat(authed).isPresent();
        assertThat(authed.get().getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void authenticate_rejectsRevokedAndExpiredAndWrongSecret() {
        ApiKeyService service = new ApiKeyService(repository);
        UUID tenantId = UUID.randomUUID();
        ApiKeyService.IssuedKey issued;
        when(repository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
        issued = service.issue(tenantId, "k", null, null);
        String prefix = issued.stored().getKeyPrefix();

        // Wrong secret.
        when(repository.findByKeyPrefix(prefix)).thenReturn(Optional.of(issued.stored()));
        assertThat(service.authenticate(prefix + ".not-the-secret")).isEmpty();

        // Revoked.
        issued.stored().setRevokedAt(LocalDateTime.now().minusMinutes(1));
        assertThat(service.authenticate(issued.plaintext())).isEmpty();

        // Expired.
        issued.stored().setRevokedAt(null);
        issued.stored().setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThat(service.authenticate(issued.plaintext())).isEmpty();
    }

    @Test
    void authenticate_rejectsMalformedInput() {
        ApiKeyService service = new ApiKeyService(repository);
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate("no-dot")).isEmpty();
        assertThat(service.authenticate(".leadingdot")).isEmpty();
        assertThat(service.authenticate("trailingdot.")).isEmpty();
    }
}
