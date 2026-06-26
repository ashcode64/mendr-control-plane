package com.selfhealing.gateway.security;

import com.selfhealing.gateway.model.ApiKey;
import com.selfhealing.gateway.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Verifies presented API keys. Keys have the form {@code <prefix>.<secret>}.
 * The prefix is an indexed lookup; the secret is compared (constant-time)
 * against the stored sha256 hash. Raw secrets are never stored.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

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
