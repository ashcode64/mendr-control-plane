package com.selfhealing.gateway.transform;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.methods.ClassicHttpRequests;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StreamingProxyClient {

    private final CloseableHttpClient httpClient;
    private final StreamingJsonTransformer transformer;

    public record StreamResult(int status, byte[] body, Map<String, String> headers) {}

    public StreamResult forward(String method, String url,
                                byte[] requestBody, Map<String, String> headers,
                                TransformProgram requestProgram,
                                TransformProgram responseProgram) throws Exception {

        byte[] outBody = transformer.transform(requestBody, requestProgram);

        HttpUriRequest request = ClassicHttpRequests.create(method, url);
        headers.forEach(request::addHeader);
        if (outBody.length > 0) {
            request.setEntity(new ByteArrayEntity(outBody, ContentType.APPLICATION_JSON));
        }

        return httpClient.execute(request, response -> {
            int status = response.getCode();
            byte[] raw = response.getEntity() != null
                    ? EntityUtils.toByteArray(response.getEntity())
                    : new byte[0];

            byte[] transformed;
            if (status >= 200 && status < 300 && !responseProgram.isEmpty()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length + 64);
                boolean ok = transformer.transform(raw, responseProgram, bos);
                transformed = ok ? bos.toByteArray() : raw;
            } else {
                transformed = raw;
            }

            Map<String, String> respHeaders = new HashMap<>();
            for (var h : response.getHeaders()) {
                respHeaders.put(h.getName(), h.getValue());
            }
            return new StreamResult(status, transformed, respHeaders);
        });
    }
}
