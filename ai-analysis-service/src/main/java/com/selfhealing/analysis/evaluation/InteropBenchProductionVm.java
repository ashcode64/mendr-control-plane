package com.selfhealing.analysis.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.transform.dsl.MendrProgram;
import com.selfhealing.gateway.transform.dsl.MendrScriptExecutor;
import com.selfhealing.gateway.transform.dsl.MendrScriptRuntimeException;
import com.selfhealing.gateway.transform.dsl.MendrScriptVerifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mode A / Mode B execution via the production Java MendrScript VM
 * ({@link MendrScriptVerifier} + {@link MendrScriptExecutor}) — not the eval toy.
 */
public final class InteropBenchProductionVm {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MendrScriptVerifier VERIFIER = new MendrScriptVerifier();
    private static final MendrScriptExecutor EXECUTOR = new MendrScriptExecutor();

    private InteropBenchProductionVm() {}

    public record Result(boolean ok, Object output, List<String> errors) {
        public static Result fail(List<String> errors) {
            return new Result(false, null, errors == null ? List.of() : List.copyOf(errors));
        }
    }

    @SuppressWarnings("unchecked")
    public static Result execute(Map<String, Object> program, Object input) {
        if (program == null) {
            return Result.fail(List.of("program is null"));
        }
        try {
            Map<String, Object> clean = new LinkedHashMap<>(program);
            clean.remove("type");
            clean.remove("_provenance");
            clean.remove("_deterministicPartial");
            clean.remove("_diagnoseResidual");
            clean.remove("_detectorProgram");
            if (!clean.containsKey("schemaVersion")) {
                clean.put("schemaVersion", MendrProgram.CURRENT_SCHEMA);
            }
            MendrProgram parsed = MAPPER.convertValue(clean, MendrProgram.class);
            MendrScriptVerifier.VerificationResult vr = VERIFIER.verify(parsed);
            if (!vr.valid()) {
                return Result.fail(vr.errors());
            }
            Object out = EXECUTOR.execute(parsed, deepCopy(input));
            return new Result(true, out, List.of());
        } catch (MendrScriptRuntimeException e) {
            return Result.fail(List.of(e.getMessage() == null ? "runtime" : e.getMessage()));
        } catch (Exception e) {
            return Result.fail(List.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object o) {
        return MAPPER.convertValue(o, Object.class);
    }
}
