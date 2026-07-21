package com.selfhealing.analysis.service.ddmin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Delta-debugging localizer with bifurcated oracle (Phase 8.3a).
 * When N&gt;1 drifted fields, finds a 1-minimal failing subset.
 */
@Slf4j
@Service
public class DdminLocalizer {

    public static final String ABORT_REASON_UNSAFE =
            "ddmin aborted: unsafe / mutating upstream opaque error — HITL required";

    @Value("${mendr.ddmin.max-fields:32}")
    private int maxFields;

    @Value("${mendr.ddmin.abort-non-safe:true}")
    private boolean abortNonSafe;

    public record FieldCandidate(String jsonPath, String changeType, String expectedType, String observedType) {}

    public record DdminResult(
            DdminOraclePath path,
            List<FieldCandidate> minimal,
            boolean aborted,
            String abortReason,
            int oracleCalls) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path.name());
            m.put("aborted", aborted);
            if (abortReason != null) m.put("abortReason", abortReason);
            m.put("oracleCalls", oracleCalls);
            m.put("minimal", minimal.stream().map(f -> {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("json_path", f.jsonPath());
                fm.put("change_type", f.changeType());
                fm.put("expected_type", f.expectedType());
                fm.put("observed_type", f.observedType());
                return fm;
            }).toList());
            return m;
        }
    }

    /**
     * @param oracle returns PASS / FAIL / UNRESOLVED for a candidate subset.
     *               Path C never invokes the oracle.
     */
    public DdminResult localize(
            String category,
            String httpMethod,
            String preciseJsonPath,
            List<FieldCandidate> fields,
            Function<List<FieldCandidate>, OracleOutcome> oracle) {
        boolean hasPrecise = preciseJsonPath != null && !preciseJsonPath.isBlank()
                && (fields == null || fields.size() <= 1);
        DdminOraclePath path = DdminOraclePath.select(
                category, httpMethod, preciseJsonPath, abortNonSafe, hasPrecise);
        return localize(path, preciseJsonPath, fields, oracle);
    }

    /** Run ddmin with a pre-selected oracle path (used by {@link DdminOracleService}). */
    public DdminResult localize(
            DdminOraclePath path,
            String preciseJsonPath,
            List<FieldCandidate> fields,
            Function<List<FieldCandidate>, OracleOutcome> oracle) {

        if (path == DdminOraclePath.SKIP_LOCALIZED) {
            List<FieldCandidate> single = fields != null && !fields.isEmpty()
                    ? List.of(fields.get(0))
                    : List.of(new FieldCandidate(preciseJsonPath, null, null, null));
            return new DdminResult(path, single, false, null, 0);
        }

        if (path.isAbort()) {
            return new DdminResult(path, List.of(), true, ABORT_REASON_UNSAFE, 0);
        }

        if (fields == null || fields.isEmpty()) {
            return new DdminResult(path, List.of(), false, "no fields", 0);
        }
        if (fields.size() == 1) {
            return new DdminResult(path, List.copyOf(fields), false, null, 0);
        }

        List<FieldCandidate> working = new ArrayList<>(fields.subList(0, Math.min(fields.size(), maxFields)));
        int[] calls = {0};
        Function<List<FieldCandidate>, OracleOutcome> counted = subset -> {
            calls[0]++;
            OracleOutcome o = oracle.apply(subset);
            return o == null ? OracleOutcome.UNRESOLVED : o;
        };

        List<FieldCandidate> minimal = ddmin(working, counted);
        return new DdminResult(path, minimal, false, null, calls[0]);
    }

    /** Classic ddmin with ternary test; UNRESOLVED subsets are skipped (not reduced). */
    List<FieldCandidate> ddmin(
            List<FieldCandidate> circ,
            Function<List<FieldCandidate>, OracleOutcome> test) {
        return ddminRec(circ, 2, test);
    }

    private List<FieldCandidate> ddminRec(
            List<FieldCandidate> circ,
            int n,
            Function<List<FieldCandidate>, OracleOutcome> test) {
        if (circ.size() <= 1) return circ;

        List<List<FieldCandidate>> subsets = split(circ, n);
        for (List<FieldCandidate> subset : subsets) {
            OracleOutcome o = test.apply(subset);
            if (o == OracleOutcome.FAIL) {
                return ddminRec(subset, 2, test);
            }
            if (o == OracleOutcome.UNRESOLVED) {
                // Do not treat as Pass or Fail — try other subsets / complements.
                continue;
            }
        }
        for (List<FieldCandidate> subset : subsets) {
            List<FieldCandidate> complement = complement(circ, subset);
            if (complement.isEmpty()) continue;
            OracleOutcome o = test.apply(complement);
            if (o == OracleOutcome.FAIL) {
                return ddminRec(complement, Math.max(n - 1, 2), test);
            }
        }
        if (n < circ.size()) {
            return ddminRec(circ, Math.min(circ.size(), 2 * n), test);
        }
        return circ;
    }

    static List<List<FieldCandidate>> split(List<FieldCandidate> circ, int n) {
        int size = circ.size();
        n = Math.max(2, Math.min(n, size));
        List<List<FieldCandidate>> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < n; i++) {
            int end = start + size / n + (i < size % n ? 1 : 0);
            out.add(new ArrayList<>(circ.subList(start, Math.min(end, size))));
            start = end;
        }
        return out;
    }

    static List<FieldCandidate> complement(List<FieldCandidate> all, List<FieldCandidate> subset) {
        List<FieldCandidate> out = new ArrayList<>();
        for (FieldCandidate f : all) {
            boolean in = false;
            for (FieldCandidate s : subset) {
                if (Objects.equals(f.jsonPath(), s.jsonPath())) {
                    in = true;
                    break;
                }
            }
            if (!in) out.add(f);
        }
        return out;
    }
}
