package com.selfhealing.analysis.service.embed;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureEmbedderTest {

    @Test
    void canonicalTextIsStableAndLowercased() {
        Map<String, Object> sig = Map.of(
                "category", "SCHEMA_MISMATCH",
                "change_type", "TYPE_COERCE",
                "json_path", "/order/amount",
                "expected_type", "integer",
                "observed_type", "string",
                "contract_coords", Map.of(
                        "service", "Payment",
                        "endpoint", "/pay",
                        "direction", "REQUEST"));

        String a = SignatureEmbedder.canonicalText(sig);
        String b = SignatureEmbedder.canonicalText(sig);
        assertThat(a).isEqualTo(b);
        assertThat(a).contains("type_coerce");
        assertThat(a).contains("/order/amount");
        assertThat(a).contains("payment");
    }

    @Test
    void hashEmbedIsDeterministicAndUnitNorm() {
        String text = "schema_mismatch|type_coerce|/order/amount|||||/pay|request";
        float[] a = SignatureEmbedder.hashEmbed(text);
        float[] b = SignatureEmbedder.hashEmbed(text);
        assertThat(a).hasSize(SignatureEmbedder.DIM);
        assertThat(a).containsExactly(b);
        double sum = 0;
        for (float v : a) sum += v * v;
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void similarSignaturesAreCloserThanUnrelated() {
        Map<String, Object> a = Map.of(
                "category", "SCHEMA_MISMATCH",
                "change_type", "TYPE_COERCE",
                "json_path", "/amount");
        Map<String, Object> b = Map.of(
                "category", "SCHEMA_MISMATCH",
                "change_type", "TYPE_COERCE",
                "json_path", "/order/amount");
        Map<String, Object> c = Map.of(
                "category", "CORS",
                "change_type", "CORS_ALLOW",
                "json_path", "/");

        float[] va = SignatureEmbedder.embedSignature(a);
        float[] vb = SignatureEmbedder.embedSignature(b);
        float[] vc = SignatureEmbedder.embedSignature(c);
        assertThat(cosine(va, vb)).isGreaterThan(cosine(va, vc));
    }

    @Test
    void toVectorLiteralFormatsPgVector() {
        float[] v = SignatureEmbedder.hashEmbed("x");
        String lit = SignatureEmbedder.toVectorLiteral(v);
        assertThat(lit).startsWith("[").endsWith("]");
        assertThat(lit.split(",")).hasSize(SignatureEmbedder.DIM);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }
}
