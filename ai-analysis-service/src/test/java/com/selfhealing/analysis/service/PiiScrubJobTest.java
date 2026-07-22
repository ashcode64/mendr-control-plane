package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PiiScrubJobTest {

    @Test
    void redactsSensitiveKeysAndEmails() throws Exception {
        PiiScrubJob job = new PiiScrubJob(null, new ObjectMapper());
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("email", "user@example.com");
        raw.put("password", "secret");
        raw.put("age", 30);
        raw.put("nested", Map.of("token", "abc", "ok", true));

        String scrubbed = job.scrubPayload(raw);
        assertTrue(scrubbed.contains("[REDACTED]") || scrubbed.contains("[EMAIL]"));
        assertTrue(scrubbed.contains("30"));
        assertFalse(scrubbed.contains("secret"));
    }

    @Test
    void sensitiveKeyDetection() {
        assertTrue(PiiScrubJob.isSensitiveKey("api_key"));
        assertTrue(PiiScrubJob.isSensitiveKey("Authorization"));
        assertFalse(PiiScrubJob.isSensitiveKey("userId"));
    }
}
