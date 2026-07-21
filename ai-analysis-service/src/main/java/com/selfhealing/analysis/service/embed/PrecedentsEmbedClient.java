package com.selfhealing.analysis.service.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Optional conversation-engine Gemini / hash embedder. Falls back to local
 * {@link SignatureEmbedder} when unset or unreachable so recall stays consistent
 * with rule-engine commits (which always hash).
 */
@Slf4j
@Component
public class PrecedentsEmbedClient {

    private final ObjectMapper objectMapper;
    private final String embedUrl;
    private final String internalApiKey;

    public PrecedentsEmbedClient(
            ObjectMapper objectMapper,
            @Value("${mendr.precedents.embed-url:}") String embedUrl,
            @Value("${gateway.internal.api-key:}") String internalApiKey) {
        this.objectMapper = objectMapper;
        this.embedUrl = embedUrl == null ? "" : embedUrl.trim();
        this.internalApiKey = internalApiKey;
    }

    public float[] embed(Map<String, Object> signature) {
        if (!embedUrl.isBlank()) {
            try {
                float[] remote = embedRemote(signature);
                if (remote != null && remote.length == SignatureEmbedder.DIM) {
                    return remote;
                }
            } catch (Exception e) {
                log.debug("CE embed unavailable, using hash: {}", e.getMessage());
            }
        }
        return SignatureEmbedder.embedSignature(signature);
    }

    @SuppressWarnings("unchecked")
    private float[] embedRemote(Map<String, Object> signature) throws Exception {
        String url = embedUrl.endsWith("/internal/embed")
                ? embedUrl
                : embedUrl.replaceAll("/$", "") + "/internal/embed";
        Map<String, Object> body = Map.of(
                "errorSignature", signature != null ? signature : Map.of(),
                "preferGemini", true);
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        String key = internalApiKey;
        if (key == null || key.isBlank()) {
            key = System.getenv("GATEWAY_INTERNAL_API_KEY");
        }
        if (key != null && !key.isBlank()) {
            req.header("X-Internal-Api-Key", key);
        }
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return null;
        }
        Map<String, Object> parsed = objectMapper.readValue(resp.body(), Map.class);
        Object emb = parsed.get("embedding");
        if (!(emb instanceof List<?> list) || list.isEmpty()) return null;
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            out[i] = v instanceof Number n ? n.floatValue() : Float.parseFloat(v.toString());
        }
        return out;
    }
}
