package com.selfhealing.analysis.config;

import com.selfhealing.analysis.service.AnthropicAnalysisClient;
import com.selfhealing.analysis.service.GeminiAnalysisClient;
import com.selfhealing.analysis.service.LlmAnalysisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
public class LlmClientConfig {

    @Bean
    @Primary
    LlmAnalysisClient llmAnalysisClient(
            @Value("${llm.provider:anthropic}") String provider,
            AnthropicAnalysisClient anthropic,
            GeminiAnalysisClient gemini) {
        String normalized = provider == null ? "" : provider.trim();
        if ("gemini".equalsIgnoreCase(normalized)) {
            log.info("LLM analysis provider selected: gemini");
            return gemini;
        }
        if (!normalized.isEmpty() && !"anthropic".equalsIgnoreCase(normalized)) {
            log.warn("Unknown LLM_PROVIDER '{}' — defaulting to anthropic", normalized);
        } else {
            log.info("LLM analysis provider selected: anthropic");
        }
        return anthropic;
    }
}
