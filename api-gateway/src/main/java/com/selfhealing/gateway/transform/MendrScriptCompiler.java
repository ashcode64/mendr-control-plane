package com.selfhealing.gateway.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.transform.dsl.MendrProgram;
import com.selfhealing.gateway.transform.dsl.Op;
import com.selfhealing.gateway.transform.dsl.ProgramSignature;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Compiles a MendrScript {@link MendrProgram} AST into the plain-JSON {@code ops[]}
 * carried by snapshot v2, and parses a stored rule definition back into an AST.
 *
 * <p>This is the v2 counterpart to {@link TransformProgramCompiler}'s legacy buckets.
 * The AST is serialized as DATA (each op becomes {@code {op, ...args}}) so the edge
 * interpreter never sees Java types — it walks the same JSON the verifier checked.
 */
@Component
public class MendrScriptCompiler {

    private static final TypeReference<List<Op>> OP_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> OPS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public MendrScriptCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Parse a stored {@code DSL_PROGRAM} rule definition ({schemaVersion, ops:[...]}) into an AST. */
    public MendrProgram parse(Map<String, Object> ruleDefinition) {
        if (ruleDefinition == null) {
            return new MendrProgram(MendrProgram.CURRENT_SCHEMA, List.of());
        }
        return objectMapper.convertValue(ruleDefinition, MendrProgram.class);
    }

    /** Serialize an AST's ops into the plain-JSON list snapshot v2 carries. */
    public List<Map<String, Object>> toSnapshotOps(MendrProgram program) {
        if (program == null || program.ops() == null || program.ops().isEmpty()) {
            return List.of();
        }
        try {
            // Type-aware write: convertValue(List<Op>, …) erases generics and drops @JsonTypeInfo "op".
            String json = objectMapper.writerFor(OP_LIST_TYPE).writeValueAsString(program.ops());
            return objectMapper.readValue(json, OPS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MendrScript ops", e);
        }
    }

    /** Serialize a single op to its plain-JSON form. */
    public Map<String, Object> toSnapshotOp(Op op) {
        try {
            String json = objectMapper.writerFor(Op.class).writeValueAsString(op);
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MendrScript op", e);
        }
    }

    /**
     * Streamability classification: a program is streamable only when it is purely
     * structural and touches no nested/document-level restructure. Conservatively,
     * any value-mutating op or any conditional forces the buffered path. (The edge
     * still re-derives this, but the snapshot flag lets v1-style fast paths skip
     * buffering when safe.)
     */
    public boolean isStreamable(MendrProgram program) {
        ProgramSignature sig = program.signature();
        if (sig.valueMutating()) {
            return false;
        }
        for (String opcode : sig.opcodes()) {
            switch (opcode) {
                case "wrap", "unwrap", "wrap_array", "unwrap_array", "move", "conditional",
                     "strip_unknown", "coalesce" -> { return false; }
                default -> { }
            }
        }
        return true;
    }
}
