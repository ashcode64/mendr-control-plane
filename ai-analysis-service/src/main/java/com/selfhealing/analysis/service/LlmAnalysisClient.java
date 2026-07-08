package com.selfhealing.analysis.service;

import com.selfhealing.analysis.service.context.StructuredFailureContext;
import com.selfhealing.analysis.service.tool.AnalysisToolResult;

/**
 * Provider-agnostic LLM analysis with structured tool/function calling.
 */
public interface LlmAnalysisClient {

    AnalysisToolResult analyze(String systemPrompt,
                               StructuredFailureContext structuredContext,
                               FailureAnalysisContext ctx);
}
