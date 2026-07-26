# Service Topology Graph & Zero-Hallucination RCA

This document describes the service-topology slice: how Mendr stores *who calls whom, over
which endpoints, and how failures cascade*, and how that deterministic graph is used to drive a
**verified, cited, abstaining** root-cause narrative. It covers the data model, the write and
read paths across control plane + data plane, the MCP tool surface, config flags, and how it is
verified (differential CTE test + faithfulness/citation-lint eval harness).

Landed alongside `infra/init_v14_service_topology.sql`.

---

## 1. Why

Failure analysis needs the *neighborhood* a failure lives in, not just the two endpoints of the
failing call: if `payment-service` is down, who is transitively affected (blast radius)? If
`order-service` is failing, which downstream dependency is the likely root cause? And critically —
when an LLM narrates that root cause, how do we guarantee it never invents a dependency that does
not exist?

Design choices:

- **Native PostgreSQL, not a bolt-on graph DB.** The topology is SCD2 (temporal) edge tables
  traversed with recursive CTEs. No new datastore, RLS for free, transactional with the rest of
  the control plane.
- **Multi-source with provenance.** The same edge can be *declared* (manifest / OpenAPI) and
  *observed* (edge traffic); each source keeps its own row + confidence so declared-vs-observed
  disagreement is preserved as reconciliation signal.
- **The graph is the ground truth; the LLM only selects from it.** Root-cause narration is
  enumerate → select → verify → cite → conformal → abstain. The model can only pick a `pathIndex`
  from a closed, enumerated set of real paths and cite real edge/node ids; every claim is
  re-checked symbolically against the live graph, and the whole narrative abstains rather than
  emit an unverifiable sentence.

---

## 2. Data model — `infra/init_v14_service_topology.sql`

Applied after `init_v13`. Idempotent. Fail-closed RLS on `app.current_tenant` (same treatment as
`init_v5`), `app_user` grants, `tenant_id` FKs.

- **`service_topology_nodes`** — one row per service (`INTERNAL` / `EXTERNAL` / `INGRESS`),
  unique per `(tenant_id, service_name)`.
- **`service_topology_edges`** — SCD2 temporal edges. Re-confirming an edge is an
  `UPDATE last_confirmed_at` (and reactivation by clearing `valid_to`), **not** a new row, so row
  count grows with topology *change*, not observation *frequency*. One row per distinct
  `source_type` (`MANIFEST_DECLARED` / `OPENAPI_DECLARED` / `TRAFFIC_OBSERVED` / `CODE_ANALYZED`),
  each with `confidence`, a canonical `edge_key` (`source>target:endpoint`), and `call_volume_7d`.
  Partial indexes over current edges (`WHERE valid_to IS NULL`) are the hot path for CTE traversal.
- **`service_topology_causal_edges`** — evidence-backed cascades: `source=upstream(root)`,
  `target=downstream(symptom)`, with the `upstream_failure_id` / `downstream_failure_id`,
  `correlation_ref`, and observed `lag_ms`. Unique on `(tenant, upstream_failure_id,
  downstream_failure_id)`.
- **`service_topology_snapshots`** — content-addressed current-adjacency oracle;
  `graph_version = sha256(canonical(adjacency))`, mirrors the `RouteProgram` hash+version model.
- **`narrative_factuality_calibration`** / **`rca_faithfulness_audit`** — conformal factuality
  calibration (global rows allowed) and per-narrative faithfulness SLO evidence.

`api_failures` also gains `correlation_id` / `request_id` / `traceparent` (+ indexes) so causal
edges are attributed by shared trace/correlation id, not by timing alone.

---

## 3. Write path (how the graph is populated)

Single write path: **`api-gateway/.../TopologyGraphWriter`** (SCD2 upsert, `edge_key`,
content-addressed snapshot + Redis `graph_version` bump). All writes stamp `tenant_id` from
`TenantContext` to satisfy the RLS `WITH CHECK`.

- **Declared edges** — `OpenApiImportService` and `ManifestImportService` call
  `recordDeclaredEdge(...)` on import, then `closeAbsentDeclaredEdges(...)` to SCD2-close edges a
  fresh declaration no longer contains, scoped by source so a different caller's edges are never
  collateral-closed: OpenAPI reconciles the `(sourceApp -> serviceName)` pair (many endpoints),
  manifest reconciles all outbound targets of `serviceName` (`targetService=null`). Both
  `rebuildSnapshot()` after import.
- **Observed edges** — the data plane samples real traffic and reports it:
  - `mendr-data-plane` `config.lua` gates it with `MENDR_EDGE_OBSERVATION_ENABLED` /
    `MENDR_EDGE_OBSERVATION_SAMPLE_RATE`; `proxy_core.lua` passes through W3C Trace Context
    (`traceparent`/`tracestate`) + B3 headers; `log.lua` posts sampled, deduped observations to
    `POST /api/internal/edge-observations`.
  - `api-gateway` `EdgeObservationController` → `EdgeObservationService` →
    `recordObservedEdge(...)` (accumulates `call_volume_7d`), then one `rebuildSnapshot()` per batch.
- **Causal edges** — `CausalCascadeBuilder` (scheduled, tenant-aware like `RuleExpirySweeper`)
  scans recent `api_failures`, groups by correlation ref (traceparent trace-id > correlation_id >
  request_id), and links each earlier failing service to each later one in the same correlated
  cascade (`source=earlier/root`, `target=later/symptom`, `lag_ms`). Idempotent via the causal
  edge's unique constraint. Kill-switch `mendr.causal.enabled`.

---

## 4. Read / query surface — `ai-analysis-service/.../TopologyQueryService`

Directionally-correct, deterministic recursive CTEs over current edges. Edge `A -> B` = "A calls
B", so **blast radius** (if X fails, who is affected) walks **backward** and **root cause** (X is
failing, what did it call) walks **forward**. Every CTE carries a mandatory `ARRAY` simple-path
cycle guard + an independent depth cap, orders deterministically, and is tenant-scoped (RLS) and
audit-logged (`audit_log`, `entity_type='service_topology'`).

Methods: `blastRadius`, `rootCauseCandidates` (causal-confirmed dependencies outrank
merely-reachable ones), `rootCausePaths` (the closed enumerated set the LLM selects from —
each path has `pathIndex` + `edgeIds`), `dependencyPaths`, `dependencyCycles`, `centralityReport`
(SPOF/fan-in), `topologyDrift`, and `verifyClaims` (Postgres as a symbolic solver).

`FailureContextEnricher.loadTopology` now sources its 1-hop `TopologyContext` from the
union-of-sources graph (so it sees observed edges Mendr never proxies), falling back to
`service_routes`. Deeper transitive reachability is served by the MCP tools below.

### Declared-vs-observed drift

`ContractReconciliationAnalyzer.analyzeTopology` lifts the request/response `MISSING_DECLARED` /
`UNDECLARED_APPEARED` framing to the whole graph at service-pair granularity:

- `OBSERVED_UNDECLARED` — traffic edge with no declared counterpart = a **shadow dependency**
  (security-shaped; never auto-heal).
- `DECLARED_UNOBSERVED` — declared edge never seen in traffic = a possibly-stale / dead dependency.

The set logic is pure/unit-testable; `TopologyQueryService.topologyDrift` does the RLS-scoped read
and delegates.

---

## 5. MCP tool surface

Registered in `ContextToolExecutor.CONTEXT_TOOLS` + `execute(...)`, so they are served by
`McpController` (`POST /mcp`, `tools/list` + `tools/call`) and reachable from the
conversation-engine via `tmcp.call_tool(...)` — **and** available to the in-process Tier-3 LLM
loops (Anthropic/Gemini) with no extra registration.

- `get_blast_radius`, `get_root_cause_candidates` (returns dependencies **and** the enumerated
  closed set of paths), `get_dependency_path`, `get_dependency_cycles`, `get_topology_drift`,
  `verify_rca_claims`.

All are read-only, deterministic ground truth; the model never invents an affected service, a
path, or an edge id.

---

## 6. Zero-hallucination RCA narrative — `conversation-engine/app/rca_narrative.py`

OFF by default (`MENDR_RCA_NARRATIVE_ENABLED`); **additive telemetry that never gates a heal**.
Pipeline: **enumerate → select → verify → cite → conformal → abstain**.

1. **Enumerate** the closed set via `get_root_cause_candidates` / `get_blast_radius` /
   `get_dependency_cycles`.
2. **Select** — strict tool-use (`select_root_cause_path`) forces the model to pick a `pathIndex`
   and assert `claims` that cite only enumerated edge/node ids. No LLM configured ⇒ abstain.
3. **Citation lint** (`lint_selection`, pure) — reject a fabricated `pathIndex`, a fabricated
   edge/node id, a `rootCauseService` not on the chosen path, an unknown service, or a narrative
   with no claim citing a chosen-path edge (ungrounded).
4. **Verify** — `verify_rca_claims` re-checks the model's claims **plus** the full structural
   chain of the chosen path against the live graph (Postgres as solver).
5. **Conformal / abstain** — render only if every claim is supported, faithfulness
   (`supported/total`) ≥ `1 - MENDR_RCA_FACTUALITY_ALPHA`, and model confidence ≥
   `MENDR_RCA_MIN_CONFIDENCE`; otherwise abstain with a reason. Returns an `audit` block
   (faithfulnessScore, supported/total, factualityAlpha) shaped for `rca_faithfulness_audit`.

`diagnose.py` attaches the result as `rcaNarrative` (behind the flag); `AiAnalysisService`
surfaces it as `_rcaNarrative` evidence in the analysis tool result. It never changes the ruleType
or the auto-heal decision.

---

## 7. Config flags

| Flag | Where | Default | Effect |
| --- | --- | --- | --- |
| `MENDR_EDGE_OBSERVATION_ENABLED` | data plane | off | Enable sampled edge observation reporting. |
| `MENDR_EDGE_OBSERVATION_SAMPLE_RATE` | data plane | `1.0` | Sample fraction of observed edges. |
| `mendr.causal.enabled` | api-gateway | `true` | Kill-switch for `CausalCascadeBuilder`. |
| `mendr.causal.lookback-minutes` / `.build-interval-ms` / `.max-group-size` / `.max-scan` | api-gateway | 30 / 60000 / 60 / 5000 | Causal builder tuning. |
| `MENDR_RCA_NARRATIVE_ENABLED` | conversation-engine | `false` | Enable the verified RCA narrative. |
| `MENDR_RCA_MAX_DEPTH` / `MENDR_RCA_MAX_PATHS` | conversation-engine | 6 / 20 | Enumeration caps. |
| `MENDR_RCA_FACTUALITY_ALPHA` | conversation-engine | `0.05` | Risk budget; faithfulness must be ≥ `1 - alpha`. |
| `MENDR_RCA_MIN_CONFIDENCE` | conversation-engine | `0.4` | Minimum model confidence before rendering. |

---

## 8. How it was verified

- **Differential CTE test** — `scripts/topology_cte_difftest.py` seeds a real Postgres with a set
  of graphs (chain, diamond, 3-cycle + tail, fan-in) and diffs the **exact** blast-radius /
  root-cause / cycle CTEs against an independent pure-Python reference (reverse/forward BFS +
  Tarjan SCCs). Proves reachability, shortest-hop depth, cycle-safe termination, and cycle
  detection agree with the intended semantics. Runs against any DB via `MENDR_DIFFTEST_PSQL`
  (e.g. `docker compose exec -T postgres psql -U admin -d selfhealing`).
- **Faithfulness / citation-lint eval harness** — `conversation-engine/tests/test_rca_narrative.py`
  is the abstention SLO: it proves a fabricated path, a fabricated edge/node id, an ungrounded
  root cause, an unsupported claim, low confidence, or a missing LLM can **never** produce a
  rendered narrative — the pipeline abstains instead.
- The full `init.sql` → `init_v14` migration chain was replayed against an ephemeral Postgres and
  RLS + blast-radius + cross-tenant isolation were exercised as `app_user`.
