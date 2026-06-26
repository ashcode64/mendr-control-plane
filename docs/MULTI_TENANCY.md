# Multi-Tenancy, Isolation & Auth

This document describes the multi-tenancy slice landed on `feat/multi-tenancy-auth`:
what was built across the control plane, the data model, the runtime isolation
guarantees, how it was verified, and **the frontend changes still required** to
finish the human-auth story.

---

## 1. Why

Mendr had **no tenancy and no auth**. Every row, cache entry, route snapshot and
Kafka message was global. To sell to more than one customer (and to build the
cross-tenant data moat) we need hard per-tenant isolation that fails closed, plus
authentication for both humans (dashboard) and machines (edge gateways).

Design choice: **shared database, shared schema, `tenant_id` on every row,
enforced by PostgreSQL Row-Level Security (RLS)** as defense-in-depth — application
bugs cannot leak across tenants because the database itself refuses to return or
write another tenant's rows.

---

## 2. What was built (backend)

### 2.1 Data model & RLS — `infra/init_v2_multitenancy.sql`

Applied **after** `infra/init.sql`. Idempotent. Adds:

- `tenants` registry (maps 1:1 to a WorkOS Organization), plus global `users` and
  `memberships` (a user can belong to many tenants).
- Per-tenant `api_keys` (machine/edge credentials, stored **hashed**).
- `edge_gateways` registry.
- A **global drift corpus** (`provider_catalog`, `drift_signatures`,
  `drift_events`) for the future cross-tenant moat — intentionally NOT
  tenant-scoped.
- A `tenant_id UUID NOT NULL` column on **every** tenant-scoped table, defaulting
  to the well-known default tenant `00000000-0000-0000-0000-000000000001` for a
  safe incremental rollout.
- `ENABLE` + `FORCE ROW LEVEL SECURITY` and a fail-closed `tenant_isolation`
  policy on each scoped table:
  ```sql
  USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
  ```
  An unset/blank context matches zero rows on read and fails writes — no leak, no
  cast error.
- A least-privilege **`app_user`** role. Critical: superusers **bypass** RLS, so
  the application must connect as `app_user` for RLS to actually apply.
  `audit_log` is append-only for `app_user` (UPDATE/DELETE revoked).

### 2.2 Tenant context plumbing (all four services)

- `TenantContext` — `ThreadLocal<UUID>` holding the current tenant, with
  `currentOrDefault()` (bound tenant, else default).
- `TenantAwareDataSource` — wraps Hikari; on connection borrow it issues
  `SET app.current_tenant = <tenant>` (default fallback) so RLS is scoped to the
  caller, and resets on return.
- `MultiTenancyProperties` / `DataSourceConfig` — bind `mendr.tenancy.*` and make
  the tenant-aware datasource primary.
- **All four services** (api-gateway, ai-analysis, rule-engine, notification) now
  connect as `app_user` and are tenant-aware (not just the gateway).

### 2.3 Authentication & authorization (api-gateway)

- **Human (dashboard):** WorkOS JWTs. When `MENDR_AUTH_WORKOS_JWKS_URI` is set, a
  `JwtDecoder` validates bearer tokens; the `org_id` claim
  (`mendr.auth.workos.org-claim`) maps to a tenant via `tenants.workos_org_id`.
- **Machine/edge:** per-tenant API keys `<prefix>.<secret>` (stored hashed),
  presented as `X-Api-Key: <key>` or `Authorization: Bearer mendr_<key>`.
- `ApiKeyAuthenticationFilter` → authenticates keys; `TenantContextFilter` → binds
  the resolved tenant for the request and clears it after.
- **Incremental rollout flag:** `MENDR_AUTH_ENFORCE=false` (default) leaves
  endpoints open but still binds tenant context from any credential present.
  `/actuator/**` and `/health` are always public. Set `true` to require auth on
  all other endpoints.

### 2.4 Write-path `tenant_id` population

The column **default alone is unsafe** for real tenants: it stamps the *default*
tenant, which mismatches a non-default connection context and is then **rejected
by RLS `WITH CHECK`**. So every write now sets `tenant_id` from context:

- JPA: a `TenantScoped` interface + `@EntityListeners(TenantEntityListener)` on
  every tenant-scoped entity (api-gateway + ai-analysis) stamps `tenant_id` on
  insert from `TenantContext` when unset.
- Raw `JdbcTemplate` inserts (rule-engine rule inserts + audit_log; api-gateway
  `route_program_history`, `audit_log`, `dns_probe_log`) set `tenant_id` from
  `TenantContext.currentOrDefault()`.

### 2.5 Kafka tenant propagation

- `TenantProducerInterceptor` stamps a `tenant_id` header on outbound records.
- `TenantRecordInterceptor` binds `TenantContext` from that header before a
  consumer processes a record, so async work stays tenant-scoped end to end.

### 2.6 Per-tenant Redis namespacing

The control-plane Redis was one shared keyspace. `TenantKeys` now prefixes every
key with `t:{tenantId}:`:

- All caches, dedup markers and evictions (`rules:`, `resp_rules:`, `cors:`,
  `route:`, `svc:url:`, `svc:auth:`, `mendr:fail-dedup:`, `mendr:validate-dedup:`).
- Route snapshot physical keys + the **sync-version counter** are per-tenant;
  `RouteConfigSnapshotPublisher` keeps `pendingSyncs`/`knownRouteKeys` as
  per-tenant maps so one tenant's publish never wakes another's edge sync. The
  *logical* route key inside the sync payload (what the edge consumes) stays
  un-namespaced.
- The in-process `RouteConfigService` L1 Caffeine cache is keyed by tenant.
- Redis pub/sub `route-changed` messages are encoded `t:{id}|{payload}`; the
  subscriber decodes, binds `TenantContext` for the republish, and clears it —
  fixing a latent bug where invalidations ran with no context.

### 2.7 Tenant-aware rule-expiry sweeper

TTL'd heals must be deactivated **and** their route republished on expiry so the
edge drops them. The old per-engine `@Scheduled` jobs ran with no tenant context,
so under RLS they only ever expired the **default tenant's** rules — every other
tenant's heals were immortal. Also, `cors_rules` and `origin_override_rules` had
no expiry at all.

`RuleExpirySweeper` is now the single owner: it iterates **every tenant** (the
`tenants` table is not RLS-scoped), binds `TenantContext` per tenant, and expires
all five TTL rule types in one pass (transformation, response-transformation,
routing, CORS, origin-override), triggering the per-tenant recompile/republish.
Per-tenant failures are isolated. Interval configurable via `mendr.expiry.*`.

---

## 3. Configuration reference

| Env var | Default | Purpose |
|---|---|---|
| `APP_DB_USERNAME` / `APP_DB_PASSWORD` | `app_user` / `app_secret` | Least-privilege DB role (RLS-enforced). **Change in prod.** |
| `MENDR_TENANCY_FALLBACK_TO_DEFAULT` | `true` | When no credential, use the default tenant (single-tenant compatible). `false` = strict. |
| `MENDR_AUTH_ENFORCE` | `false` | Require auth on all non-health endpoints. |
| `MENDR_AUTH_WORKOS_JWKS_URI` | _(unset)_ | WorkOS JWKS endpoint; enables JWT validation when set. |
| `MENDR_AUTH_WORKOS_ISSUER` / `_AUDIENCE` | _(unset)_ | JWT issuer / audience validation. |
| `MENDR_AUTH_WORKOS_ORG_CLAIM` | `org_id` | JWT claim mapped to `tenants.workos_org_id`. |
| `MENDR_EXPIRY_SWEEP_INTERVAL_MS` | `60000` | Rule-expiry sweep cadence. |
| `MENDR_EXPIRY_INITIAL_DELAY_MS` | `30000` | Delay before first sweep after boot. |

---

## 4. How it was verified

- **Write path / RLS:** against live Postgres, an insert under a non-default
  tenant *without* an explicit `tenant_id` is rejected by RLS `WITH CHECK`;
  stamping the bound tenant succeeds; stamping a *different* tenant is rejected.
- **Redis isolation:** booted gateway writes every key under `t:{tenant}:`; two
  tenants writing the same logical key don't collide; per-tenant `SCAN` returns
  only that tenant's keys; sync-version counters are independent.
- **Expiry sweeper:** on a booted gateway with an expired rule seeded for two
  tenants, one sweep logged `2 rule(s) expired across 2 tenant(s)`, flipped both
  rows inactive, and republished each route into its own tenant Redis namespace.
- **Tests:** api-gateway 93, ai-analysis 36, rule-engine 1 — all green; all four
  modules boot as `app_user` against a migrated DB.

---

## 5. Frontend changes required (NOT in this PR)

The backend is wired and runs today (with `MENDR_AUTH_ENFORCE=false`). The
**dashboard frontend has no auth yet** — it talks to `/api/*` with plain axios and
no tenant credential (`frontend/src/utils/api.js`). To turn on real human auth and
per-tenant dashboards, the frontend needs:

1. **WorkOS AuthKit login flow**
   - Add `@workos-inc/authkit-react` (or the hosted AuthKit redirect flow).
   - Wrap the app in the AuthKit provider; gate routes behind an authenticated
     session; add sign-in / sign-out and a callback route.
   - On login, WorkOS issues a JWT whose `org_id` claim identifies the tenant.

2. **Attach the JWT to every API call**
   - In `frontend/src/utils/api.js`, add a **request interceptor** to each axios
     client (`gateway`, `analysis`, `rules`, `services`) that sets
     `Authorization: Bearer <access_token>` from the AuthKit session.
   - Refresh the token on expiry; on `401`, redirect to login.

3. **Org / tenant switching**
   - If a user belongs to multiple WorkOS organizations, add an org switcher;
     re-auth (or swap the active token) so the `org_id` claim — and therefore the
     server-side tenant — changes. All data is already scoped by the backend from
     that claim; the UI just needs to surface the active org.

4. **Handle enforced auth**
   - Once the backend sets `MENDR_AUTH_ENFORCE=true`, unauthenticated calls return
     `401`. Ensure the global response interceptor (already present for error
     normalization) redirects to login on `401` rather than surfacing a raw error.

5. **API-key management UI (optional, machine creds)**
   - Screens under the tenant to create/list/revoke per-tenant `api_keys` for edge
     gateways (the secret is shown once; only the hash is stored). Backend
     endpoints for key issuance are the prerequisite if not already present.

6. **Build/runtime config**
   - Add `REACT_APP_WORKOS_CLIENT_ID` (+ redirect URI / AuthKit config) to the
     frontend env and `docker-compose`/build wiring.

Until these land, keep `MENDR_AUTH_ENFORCE=false` so the dashboard keeps working
against the default tenant.

---

## 6. Honest remaining gaps (backend)

- **Warm-publish on startup** runs under the default tenant only; real tenants get
  populated on demand via (now tenant-encoded) route-change events. Eager
  per-tenant warm publish is a small follow-up.
- The **anonymizer job** feeding `drift_signatures` (the cross-tenant moat) is not
  implemented yet.
- WorkOS org **provisioning/SCIM** sync into `tenants`/`users`/`memberships` is not
  automated yet.
