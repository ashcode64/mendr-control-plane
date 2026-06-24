package com.selfhealing.analysis.service.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Category-scoped, nested JSON payload sent to the model as the user turn.
 *
 * <p>Replaces the legacy flat {@code "Label: value\n"} prompt. Sections that are
 * irrelevant to the failure category are {@code null} and dropped via
 * {@link JsonInclude.Include#NON_NULL}, so a SCHEMA_MISMATCH never carries CORS
 * origin lists and a CORS failure never carries DNS-probe history. The structure
 * itself does the disambiguation the old {@code FIELD_GLOSSARY} prose strained to do.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StructuredFailureContext(
        String failureId,
        String category,
        String httpMethod,
        String endpoint,
        Integer httpErrorCode,
        String errorMessage,
        RoutingContext routing,
        CorsContext cors,
        SchemaContext schema,
        TopologyContext topology,
        DeterministicFinding deterministicFinding,
        List<PriorAttempt> priorAttempts
) {
}
