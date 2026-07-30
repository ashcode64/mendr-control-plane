# mendr-minimize

Deterministic MendrScript remediation minimization sidecar (L2 necessity + L3 egg EqSat + L4 prove_minimal).

## Layers

1. **necessity** — ddmin over `ops[]` (1-minimal subsequence; empty/singleton included).
   Ternary oracle: Pass/Fail/**Unresolved**. Paths containing `oneOf`/`anyOf` (or listed in
   `unresolvablePaths`) are never coerced to Pass — ops on those paths are not dropped.
2. **eqsat** — structured `egg` language (`MsLang`) with `rewrite!` rules (rename/move compose,
   wrap/unwrap cancel, coerce shadow, dead-default+remove). Context-sensitive seeds (disjoint
   commute, twin-gated schema coerce) are unioned into the same e-class before `Runner` saturates.
   Extract once via `Extractor` + cost `(opCount, valueMutating)`.
   Twin-gated coerce removal requires `specTrust ≥ gate` **and** explicit `triggeringPayload`
   **and** `declaredFieldTypes`.
3. **prove_minimal** — SyGuS-lite: size-gated subsequence search over the draft and adjacent
   path-disjoint reorderings + CEGIS (`k ≤ 8`); respects `allowedOpcodes`. Does not invent
   new ops from arbitrary paths.

Empty programs over a non-empty draft are **not** reported as improvements (undeployable via approve).

`fellBack` is always `false` from this service — the Java gateway sets `fellBack` when the sidecar
is down or mandatory re-verify fails.

## Bind

- Default: `127.0.0.1:8099` (`MENDR_MINIMIZE_BIND`)
- Docker: `0.0.0.0:8099`

Docker: built from this directory; compose service `mendr-minimize`. CI: `.github/workflows/mendr-minimize.yml`.

## API

`POST /minimize`

```json
{
  "program": { "schemaVersion": "mendrscript/v1", "ops": [] },
  "cases": [{ "input": {}, "expected": null }],
  "triggeringPayload": {},
  "specTrust": 0.9,
  "allowedOpcodes": ["rename"],
  "declaredFieldTypes": { "/amount": "integer" },
  "unresolvablePaths": ["/payload/oneOf/0/x"],
  "specTrustGate": 0.85,
  "proveMinimalMaxOps": 8
}
```

`GET /health` → `ok`

## Parity fixtures

`fixtures/parity_cases.json` exercises probe-oracle semantics aligned with Java
`MendrScriptExecutor` / `JsonPointers` (coalesce present-null, scale multiply/divide order,
rename, nested default). Loaded by `cargo test` (`parity_fixtures_match_expected`).
Final correctness is always re-checked by Java re-verify in the gateway facade.
