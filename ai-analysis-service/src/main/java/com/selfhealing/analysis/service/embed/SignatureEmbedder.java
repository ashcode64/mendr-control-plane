package com.selfhealing.analysis.service.embed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Deterministic 768-d hashing embedder for ErrorSignatures.
 * Must stay byte-compatible with conversation-engine {@code app.embeddings.hash_embed}.
 */
public final class SignatureEmbedder {

    public static final int DIM = 768;

    private SignatureEmbedder() {}

    /** Canonical text used for both hash and optional Gemini embedding. */
    public static String canonicalText(Map<String, Object> sig) {
        if (sig == null || sig.isEmpty()) return "";
        StringJoiner j = new StringJoiner("|");
        j.add(norm(sig.get("category")));
        j.add(norm(sig.get("change_type")));
        j.add(norm(sig.get("json_path")));
        j.add(norm(sig.get("template_id")));
        j.add(norm(sig.get("expected_type")));
        j.add(norm(sig.get("observed_type")));
        j.add(norm(sig.get("contract_ref")));
        Object coords = sig.get("contract_coords");
        if (coords instanceof Map<?, ?> c) {
            j.add(norm(c.get("service")));
            j.add(norm(c.get("endpoint")));
            j.add(norm(c.get("direction")));
        }
        return j.toString();
    }

    public static float[] hashEmbed(String text) {
        float[] vec = new float[DIM];
        if (text == null || text.isBlank()) {
            return l2Normalize(vec);
        }
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^a-z0-9_/.-]+");
        for (String token : tokens) {
            if (token.isBlank()) continue;
            byte[] digest = sha256(token.getBytes(StandardCharsets.UTF_8));
            int bucket = ((digest[0] & 0xff) << 8 | (digest[1] & 0xff)) % DIM;
            float sign = (digest[2] & 1) == 0 ? 1f : -1f;
            float weight = 1f + ((digest[3] & 0xff) / 255f);
            vec[bucket] += sign * weight;
        }
        // Also mix full-string hash for short texts with few tokens
        byte[] full = sha256(text.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 16; i++) {
            int idx = ((full[i] & 0xff) << 4 | (full[(i + 1) % 16] & 0x0f)) % DIM;
            vec[idx] += ((full[(i + 2) % 16] & 1) == 0 ? 1f : -1f);
        }
        return l2Normalize(vec);
    }

    public static float[] embedSignature(Map<String, Object> sig) {
        return hashEmbed(canonicalText(sig));
    }

    /** pgvector literal: '[v1,v2,...]' */
    public static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(vec[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String norm(Object o) {
        if (o == null) return "";
        return o.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static float[] l2Normalize(float[] vec) {
        double sum = 0;
        for (float v : vec) sum += (double) v * v;
        if (sum <= 1e-12) {
            vec[0] = 1f;
            return vec;
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < vec.length; i++) vec[i] *= inv;
        return vec;
    }
}
