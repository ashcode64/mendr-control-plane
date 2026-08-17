package com.selfhealing.gateway.security;

import com.selfhealing.gateway.model.ApiKey;
import com.selfhealing.gateway.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies presented API keys. Keys have the form {@code <prefix>.<secret>}.
 * The prefix is an indexed lookup; the secret is compared (constant-time)
 * against the stored sha256 hash. Raw secrets are never stored.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final ApiKeyRepository apiKeyRepository;

    /** The raw key ({@code <prefix>.<secret>}) returned ONCE at issuance time. */
    public record IssuedKey(ApiKey stored, String plaintext) {}

    /**
     * Issue a new per-tenant API key. The high-entropy secret is returned ONCE and
     * only its sha256 hash is persisted (raw secret is never stored). Callers must
     * surface {@link IssuedKey#plaintext()} to the operator immediately and discard it.
     */
    public IssuedKey issue(UUID tenantId, String name, UUID createdBy, LocalDateTime expiresAt) {
        return issue(tenantId, name, createdBy, expiresAt, null);
    }

    /**
     * Issue a new per-tenant API key with optional OAuth-style scopes for edge authPolicy checks.
     */
    public IssuedKey issue(UUID tenantId, String name, UUID createdBy, LocalDateTime expiresAt, String[] scopes) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String prefix = "mendr_" + randomToken(9);
        String secret = randomToken(32);
        ApiKey key = ApiKey.builder()
                .tenantId(tenantId)
                .name(name)
                .keyPrefix(prefix)
                .keyHash(sha256Hex(secret))
                .createdBy(createdBy)
                .expiresAt(expiresAt)
                .scopes(scopes)
                .createdAt(LocalDateTime.now())
                .build();
        ApiKey saved = apiKeyRepository.save(key);
        return new IssuedKey(saved, prefix + "." + secret);
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return B64.encodeToString(buf);
    }

    public Optional<ApiKey> authenticate(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        int sep = presented.lastIndexOf('.');
        if (sep <= 0 || sep == presented.length() - 1) {
            return Optional.empty();
        }
        String prefix = presented.substring(0, sep);
        String secret = presented.substring(sep + 1);
        String presentedHash = sha256Hex(secret);

        return apiKeyRepository.findByKeyPrefix(prefix)
                .filter(k -> k.getRevokedAt() == null)
                .filter(k -> k.getExpiresAt() == null || k.getExpiresAt().isAfter(LocalDateTime.now()))
                .filter(k -> constantTimeEquals(k.getKeyHash(), presentedHash));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String stored, String presented) {
        if (stored == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
