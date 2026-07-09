package com.selfhealing.analysis.config;

import com.selfhealing.analysis.service.AnthropicAnalysisClient;
import com.selfhealing.analysis.service.GeminiAnalysisClient;
import com.selfhealing.analysis.service.LlmAnalysisClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LlmClientConfigTest {

    @Mock
    private AnthropicAnalysisClient anthropic;

    @Mock
    private GeminiAnalysisClient gemini;

    private final LlmClientConfig config = new LlmClientConfig();

    @Test
    void defaultsToAnthropic() {
        LlmAnalysisClient client = config.llmAnalysisClient("anthropic", anthropic, gemini);
        assertThat(client).isSameAs(anthropic);
    }

    @Test
    void selectsGeminiWhenConfigured() {
        LlmAnalysisClient client = config.llmAnalysisClient("gemini", anthropic, gemini);
        assertThat(client).isSameAs(gemini);
    }

    @Test
    void unknownProviderDefaultsToAnthropic() {
        LlmAnalysisClient client = config.llmAnalysisClient("gemeni", anthropic, gemini);
        assertThat(client).isSameAs(anthropic);
    }
}
