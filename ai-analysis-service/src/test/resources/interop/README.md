# InteropBench fixtures

Golden schema-pair fixtures for Mendr InteropBench (Mode A CI / Mode B nightly).

Loaded by `com.selfhealing.analysis.evaluation.InteropBenchFixtures` from
`classpath:interop/fixtures.json` (single JSON array).

## Axes (do not collapse)

| Field | Values |
|---|---|
| `class` | `structural` · `value` · `negative` |
| `llm_difficulty` | `easy` · `medium` · `hard` |
| `plan_class` | `FORWARD_ONLY` · `BOUNDED_WINDOW` · `UNBOUNDED` · `null` (negatives only) |

Structural and value fixtures are crossed on difficulty × plan class. Negatives are
uncrossed (`plan_class: null`) — they assert detector abstention, not a program class.

## Fixture object schema

```json
{
  "id": "string",
  "class": "structural|value|negative",
  "llm_difficulty": "easy|medium|hard",
  "plan_class": "FORWARD_ONLY|BOUNDED_WINDOW|UNBOUNDED|null",
  "source_schema": {},
  "target_schema": {},
  "input": {},
  "golden_output": {},
  "expected_mismatches": ["FIELD_RENAME|TYPE_MISMATCH|MISSING_FIELD|FIELD_MOVE|UNIT_SCALE|DATE_FORMAT"],
  "expected_program": { "schemaVersion": "mendrscript/v1", "ops": [] },
  "assert_no_p0_detector": true
}
```

- `expected_program` is optional (present for structural/value goldens with a known program).
- Negatives **must** set `assert_no_p0_detector: true` and **must not** list `UNIT_SCALE` or
  `DATE_FORMAT` in `expected_mismatches`.

## P0 value class (unit + date)

Closed-registry conversions only:

| Kind | Field tokens | Factor / formats |
|---|---|---|
| Unit | `kmh`↔`mph`, `m`↔`ft`, `kg`↔`lbs` | `×0.621371`, `×3.28084`, `×2.20462` |
| Date | `iso8601`↔`epoch_s` / `epoch_ms` | `2020-01-01T00:00:00Z` → `1577836800` / `1577836800000` |

Expected programs use `rename` + `scale` or `rename` + `reformat_date`.

## Dotted literal keys

`s_dotted_key_pointer_hard_fo` renames `cpu_usage_pct` → `system.cpu.utilization`.
MendrScript JSON Pointer treats `.` as an ordinary character in a single path segment
(`/system.cpu.utilization`). Dot-notation nest setters that split on `.` cannot set a
literal dotted key — this fixture is the architectural claim, not a vocabulary detector.

## Negatives (D7 precision)

| id | Why the P0 detector must abstain |
|---|---|
| `n_parking_meters` | Unit token `meters` in an unrelated field name |
| `n_address_feet` | `feet` appears in a street string, not a convertible pair |
| `n_unit_one_side` | Unit token on source only |
| `n_unit_string_type` | Unit-named fields with string values (registry requires numbers) |
| `n_same_unit_diff_name` | `temp_c` → `celsius` rename, no conversion pair |
| `n_date_already_target` | Both sides already `iso8601` |
| `n_neighbor_feet_no_pair` | `feet` only in free-text notes; numeric field has no pair |

Acceptance: **zero** `UNIT_SCALE` / `DATE_FORMAT` fires across the negative class.

## Modes

- **Mode A (CI):** analyzer → program → Java execute → golden; N=1; gates CI.
- **Mode B (nightly):** full diagnose/LLM path; N=10; does not gate CI.

Freeze baseline results before merging detector/gate changes; re-run the same fixture set
for the acceptance table.
