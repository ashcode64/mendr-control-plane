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

- `ANTHROPIC_API_KEY`
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

Fresh `docker compose up` on a new volume applies `init.sql` → `init_v2_*` → `init_v3_*`
automatically.

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

## Ports

- `3000` dashboard
- `8095` api-gateway
- `8082` ai-analysis-service
- `8083` notification-service
- `8084` rule-engine
