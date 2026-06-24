package com.selfhealing.analysis.service.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The deterministic analyzer's confident verdict, handed to the model as a fact
 * rather than something it must re-derive. When {@code hasConfidentMatch} is true,
 * the engine forces the matching tool and the model only fills in narrative.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeterministicFinding(
        boolean hasConfidentMatch,
        String kind,
        String summary,
        Map<String, Object> structuredDiff
) {
    public static DeterministicFinding none() {
        return new DeterministicFinding(false, null, null, null);
    }
}
