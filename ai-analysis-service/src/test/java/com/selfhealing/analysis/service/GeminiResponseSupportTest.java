package com.selfhealing.analysis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.GeminiResponseSupport;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;
import com.selfhealing.analysis.service.tool.AnalysisTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiResponseSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesFunctionCallFromSuccessfulResponse() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "functionCall": {
                          "name": "propose_field_rename",
                          "args": {
                            "mappings": {"old": "new"},
                            "confidence": 0.9,
                            "rootCause": "name mismatch"
                          }
                        }
                      }]
                    }
                  }]
                }
                """);

        JsonNode functionCall = GeminiResponseSupport.firstFunctionCall(response);
        assertThat(functionCall).isNotNull();
        assertThat(functionCall.path("name").asText()).isEqualTo("propose_field_rename");

        Map<String, Object> args = GeminiResponseSupport.functionArgs(functionCall, objectMapper);
        assertThat(args).containsEntry("confidence", 0.9);

        String ruleType = AnalysisTools.ruleTypeForTool(functionCall.path("name").asText());
        AnalysisToolResult result = AnalysisToolResult.fromToolInput(
                AnalysisToolResult.Source.GEMINI, "gemini-2.0-flash",
                functionCall.path("name").asText(), args);
        assertThat(result.ruleType()).isEqualTo(ruleType);
        assertThat(result.source()).isEqualTo(AnalysisToolResult.Source.GEMINI);
    }

    @Test
    void rejectsApiErrorPayload() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {"error": {"message": "API key invalid", "code": 400}}
                """);

        assertThatThrownBy(() -> GeminiResponseSupport.ensureSuccess(response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key invalid");
    }

    @Test
    void rejectsEmptyCandidates() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "candidates": [],
                  "promptFeedback": {"blockReason": "SAFETY"}
                }
                """);

        assertThatThrownBy(() -> GeminiResponseSupport.ensureSuccess(response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY");
    }

    @Test
    void collectsMultipleFunctionCalls() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "candidates": [{
                    "content": {
                      "parts": [
                        {"functionCall": {"name": "get_contract", "args": {"service": "a"}}},
                        {"functionCall": {"name": "propose_field_rename", "args": {"mappings": {}}}}
                      ]
                    }
                  }]
                }
                """);

        List<JsonNode> calls = GeminiResponseSupport.allFunctionCalls(response);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).path("name").asText()).isEqualTo("get_contract");
    }
}
