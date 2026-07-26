# Mendr Control Plane

This repository contains the cloud/on-prem control-plane services for Mendr:

- `api-gateway`
- `ai-analysis-service`
- `rule-engine`
- `notification-service`
- `frontend`
- supporting infrastructure: PostgreSQL, Redis, Zookeeper, Kafka

## Purpose

The control plane owns:

- service registration and contracts (including optional `allowedCallerOrigins` per service)
- failure ingestion and response validation
- AI analysis
- rule approval and deployment
- dashboard UI

The separate `mendr-data-plane` repository should be deployed at the customer edge and forwards registration calls here while keeping proxy traffic local.

### Service registration CORS

When registering a service, include optional `allowedCallerOrigins` in the JSON body:

```json
{
  "name": "payment-service",
  "baseUrl": "http://localhost:8091",
  "allowedCallerOrigins": ["http://localhost:8090"]
}
```

The control plane stores this on the service record and syncs it into `cors_rules`. The edge data plane enforces it from route snapshots (no per-request control-plane call).

## Run

```powershell
docker compose up -d --build
```

## Required environment

- `LLM_PROVIDER` — `anthropic` (default) or `gemini`. Set the API key for the active provider:
  - Anthropic: `ANTHROPIC_API_KEY`, optional `ANTHROPIC_MODEL`
  - Gemini: `GEMINI_API_KEY`, optional `GEMINI_MODEL` (default `gemini-2.0-flash`)
- Use the **same** `LLM_PROVIDER` value for `ai-analysis-service` and `conversation-engine` so failure analysis and MendrScript chat use the same LLM backend.
- `GATEWAY_INTERNAL_API_KEY` for trusted edge/control-plane calls and MendrScript
  chat persistence (`conversation-engine` → `ai-analysis-service` internal APIs).
  Set the same value in `.env` for `api-gateway`, `ai-analysis-service`, and
  `conversation-engine`.

### Chat persistence migration

If your Postgres volume was created before `init_v3_analysis_conversations.sql`
was added, apply it manually (idempotent):

```powershell
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v3_analysis_conversations.sql
```

### ErrorSignature / GraphRAG migration (Phases 4–6)

Compose now uses **`pgvector/pgvector:pg15`** (not stock `postgres:15-alpine`) and
mounts `infra/init_error_signature.sql` + `infra/init_v5_error_precedents.sql`
(`CREATE EXTENSION vector`, `error_precedents`, tenant RLS).

**Existing volumes do not re-run init scripts.** Either recreate the volume, or
apply manually:

```powershell
# Requires a pgvector-capable image already running
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_error_signature.sql
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v4_openapi.sql
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v5_error_precedents.sql
```

If the volume was created on non-pgvector Postgres, recreate it (e.g.
`docker compose down -v` then `up`) so the vector extension can install.

Fresh `docker compose up` on a new volume applies `init.sql` → `init_v2_*` →
`init_v3_*` → `init_v4_*` → `init_v5_*` → `init_v6_*` → `init_v7_*` automatically.

### Self-learning substrate (Phase 0)

Existing volumes do not re-run init scripts. Apply manually (idempotent):

```powershell
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v6_phase8_moat.sql
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v7_self_learning.sql
```

Creates `learning_traces`, `counterexample_suite`, and `offline_regression_payloads`
(with `scrub_status` PENDING/COMPLETED/FAILED for async PII scrub).

### Phase 1 ACE + RegressionHarness

```powershell
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v8_phase1_regression_ace.sql
```

Creates `ace_playbook` and `regression_harness_runs`.

### Phase 2 topology-scoped repair heuristics

```powershell
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v9_phase2_repair_heuristics.sql
```

Creates `repair_heuristics` (required `topology_scope`; ExpeL Reflector/Curator).

### Phase 3 LILO skills + MetaMemory

```powershell
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v10_phase3_lilo_metamemory.sql
```

Creates `skill_library`, `meta_memory`, and `error_precedents.archived_at` (Semantic Memory archive).

### Phase 5 EvolveMem retrieval config

```powershell
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v11_phase5_evolvemem.sql
```

Creates versioned `retrieval_config` (topK / thresholds / decay; harness promote+revert).

### Phase 6 GEPA compiled prompts

```powershell
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v12_phase6_gepa.sql
```

Creates `compiled_prompts`. Enable with `MENDR_GEPA_ENABLED=true` and
`MENDR_DSPY_PII_SCRUB_APPROVED=true` after scrub is proven (≥5 COMPLETED offline payloads).
Optional DSPy path: `MENDR_DSPY_ENABLED=true` + `MENDR_CONVERSATION_GEPA_COMPILE_URL`.

### Phase 7 Cross-tenant pool (opt-in)

```powershell
docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v13_phase7_cross_tenant.sql
```

Creates `cross_tenant_opt_in`, `cross_tenant_pool`, `cross_tenant_imports`.
**Default OFF.** Enable only after contractual privacy review:

```
MENDR_CROSS_TENANT_ENABLED=true
```

Then `POST /internal/cross-tenant/opt-in` with explicit privacy attestation:

```json
{
  "publishEnabled": true,
  "importEnabled": true,
  "privacyReviewed": true,
  "reviewedBy": "privacy-officer@example.com",
  "notes": "contract X reviewed YYYY-MM-DD"
}
```

Bare toggles without `privacyReviewed=true` + `reviewedBy` are rejected.
When `MENDR_AUTH_ENFORCE=true`, `/internal/cross-tenant/**` requires authentication.
Publish anonymizes skills/heuristics/playbook; import requires local critic + RegressionHarness.
Diagnose and Tier-3/MCP agents never see raw pool payloads — use
`GET /internal/cross-tenant/pool` + `POST /import` only; after ACCEPTED import,
local `match_skill` / heuristics / playbook serve diagnose.

## Multi-tenancy, isolation & auth

Isolation is enforced by Postgres Row-Level Security. `infra/init_v2_multitenancy.sql`
(applied after `init.sql`) adds a `tenants` registry, `users`/`memberships`,
per-tenant `api_keys`, a global drift corpus, a `tenant_id` column + fail-closed
RLS policy on every tenant-scoped table, and a least-privilege `app_user` role.

Key operational facts:

- The api-gateway connects as **`app_user`** (non-superuser) so RLS is actually
  enforced — superusers bypass it. Configure via `APP_DB_USERNAME` / `APP_DB_PASSWORD`
  (defaults `app_user` / `app_secret`; change in production).
- Each request binds a tenant (`app.current_tenant`) for the connection. When no
  credential is present it falls back to the default tenant
  (`00000000-0000-0000-0000-000000000001`), preserving single-tenant behavior.
  Set `MENDR_TENANCY_FALLBACK_TO_DEFAULT=false` for strict isolation.
- **Human auth (WorkOS):** set `MENDR_AUTH_WORKOS_JWKS_URI` (+ `_ISSUER`, `_AUDIENCE`)
  to validate dashboard JWTs; the `org_id` claim maps to a tenant via `tenants.workos_org_id`.
- **Machine/edge auth:** per-tenant API keys (`<prefix>.<secret>`, stored hashed)
  presented as `X-Api-Key` or `Authorization: Bearer mendr_...`.
- **Enforcement:** `MENDR_AUTH_ENFORCE=false` (default) leaves endpoints open but
  still binds tenant context from any credential — a safe incremental rollout.
  Set `true` to require auth on all non-health endpoints.

All four services (api-gateway, ai-analysis, rule-engine, notification) connect as
`app_user` and are tenant-aware: writes stamp `tenant_id` from context (satisfying
RLS `WITH CHECK`), Kafka messages carry a `tenant_id` header, Redis keys are
namespaced `t:{tenantId}:`, and a tenant-aware sweeper expires TTL rules across all
tenants. See **[docs/MULTI_TENANCY.md](docs/MULTI_TENANCY.md)** for the full design,
configuration reference, verification, and **the frontend changes still required**
to enable human (WorkOS) auth.

## Service topology & zero-hallucination RCA

A native-PostgreSQL, SCD2 service-dependency graph (declared + observed + causal edges) drives
deterministic blast-radius / root-cause / drift queries, and a verified, cited, **abstaining**
LLM root-cause narrative that can only select from the enumerated real paths. See
**[docs/SERVICE_TOPOLOGY_RCA.md](docs/SERVICE_TOPOLOGY_RCA.md)** for the data model, write/read
paths, MCP tools, config flags, and the differential CTE + faithfulness test harnesses.

## Ports

- `3000` dashboard
- `8095` api-gateway
- `8082` ai-analysis-service
- `8083` notification-service
- `8084` rule-engine
