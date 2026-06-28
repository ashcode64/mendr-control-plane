package com.selfhealing.gateway.transform.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A complete MendrScript program: a closed-vocabulary, verifiable AST that the
 * conversation engine synthesizes, the verifier checks, the Java executor runs
 * (slow path / simulation / shadow), and the edge interpreter runs (hot path).
 *
 * <p>The AST is data, not code. {@code schemaVersion} lets the verifier and the
 * edge reject programs they do not understand.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MendrProgram(String schemaVersion, List<Op> ops) {

    public static final String CURRENT_SCHEMA = "mendrscript/v1";

    public MendrProgram {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = CURRENT_SCHEMA;
        }
        ops = ops == null ? List.of() : List.copyOf(ops);
    }

    public ProgramSignature signature() {
        return ProgramSignature.of(this);
    }
}
