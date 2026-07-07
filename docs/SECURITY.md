# Mendr Security Model

This document is the single overview of Mendr's security posture across the control
plane, the AI chatbot (conversation engine + MCP), and the SaaS edge data plane. It
complements [`MULTI_TENANCY.md`](MULTI_TENANCY.md) (the tenancy/RLS deep-dive).

## 1. Threat model at a glance

- Multiple customer tenants share one database and one control plane; a bug must
  never leak one tenant's data/rules to another.
- The AI chatbot turns natural language into transformation programs. It must never
  gain the ability to deploy on its own, exfiltrate secrets, or be steered by
  prompt injection into unsafe programs.
- The edge data plane runs on customer infrastructure and syncs route config from
  the control plane; it must only ever receive its own tenant's routes.

## 2. Identity & authentication

| Caller | Mechanism | Tenant resolution |
|---|---|---|
| Human (dashboard) | WorkOS JWT (validated via JWKS) at every service it reaches | `org_id` claim -> `tenants.workos_org_id` |
| Edge / machine | Per-tenant API key `<prefix>.<secret>` (sha256-hashed at rest) | key row's `tenant_id` |
| Internal service-to-service | Shared `GATEWAY_INTERNAL_API_KEY` | trusted `X-Tenant-Id` (UUID or org id) |

Every HTTP-serving Java service runs the **same three-layer inbound stack** so no
service accepts an unvetted call (deliberately duplicated per service — see the
checklist in §10):

1. **OAuth2 resource server** validates the WorkOS JWT via JWKS (humans).
2. **Internal-key filter** turns a valid `X-Internal-Api-Key` into a trusted
   `ROLE_INTERNAL` principal (machines). This is the only principal allowed to
   assert a tenant via `X-Tenant-Id`.
3. **Tenant filter** binds `TenantContext` for RLS, then clears it: JWT →
   `org_id` claim → `tenants.workos_org_id`; internal → asserted `X-Tenant-Id`
   (UUID or org id, mapped + cached); otherwise unset (default tenant).

- api-gateway: `SecurityConfig` + `ApiKeyAuthenticationFilter` (per-tenant edge
  keys) + `TenantContextFilter`.
- ai-analysis: `security/{SecurityConfig, InternalApiKeyAuthFilter,
  TenantContextFilter, TenantResolver}`. `/mcp` is machine-only; `/api/analysis`
  accepts the dashboard JWT.
- rule-engine: same `security/*` stack; protects `/api/rules`.
- conversation-engine: `auth.py` validates the WorkOS JWT / internal key and
  forwards the tenant to the MCP surface alongside the internal key.
- notification-service: Kafka-only; its HTTP surface is `denyAll` by default.
- A raw `X-Tenant-Id` from an untrusted client is never honoured (no trusted
  principal ⇒ ignored), so it cannot be used to read another tenant.
- `/actuator/**`, `/error`, and `/api/internal/**` (shared-key guarded) stay
  outside JWT enforcement.

Enforcement is gated by `MENDR_AUTH_ENFORCE` (default `false`) for a safe
incremental rollout: credentials still bind the tenant, but requests are not
rejected until the flag is flipped (see §7).

## 3. Authorization & isolation (defense-in-depth)

- **Postgres RLS** on every tenant-scoped table (fail-closed: unset context matches
  zero rows). Services connect as the least-privilege `app_user` (superusers bypass
  RLS). The chatbot's `transform_programs` table is included.
- **Complete mediation:** authorization happens in the downstream systems (RLS, the
  gateway verifier), never delegated to the LLM.
- **Per-tenant Redis** namespacing, Kafka `tenant_id` header propagation, per-tenant
  route snapshots + sync-version counters, tenant-aware expiry sweeper.

## 4. AI chatbot safety (OWASP LLM Top 10)

- **LLM01 Prompt injection:** immutable system prompt; user text/context treated as
  data; input action-screening flags injection/deploy attempts; constrained tool-use
  restricts output to the closed MendrScript opcode vocabulary.
- **LLM02 Sensitive info disclosure:** assistant output is scrubbed of
  credential-shaped tokens before it leaves the service; protected fields are
  refused by the verifier.
- **LLM06 Excessive agency:** the engine has NO deploy capability; every program is
  re-verified server-side by the authoritative Java `MendrScriptVerifier`, and deploy
  requires a human approval gate that lives outside the model.
- **LLM10 Unbounded consumption:** per-tenant + per-client rate limiting, bounded
  message/context size, bounded refine iterations and `max_tokens`.

## 5. Transport & network

- Terminate TLS at the ingress; run all inter-service traffic (HTTP, Kafka, Redis,
  Postgres) on a private network / service mesh. Do not expose `/mcp`,
  `/api/internal`, Kafka, Redis, or Postgres to untrusted networks.
- Dashboard served with CSP, HSTS, `X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy` (see `frontend/nginx.conf`); CORS is allow-listed per service
  (never `*`).
- Recommended follow-up: mTLS between services (mesh or per-service certs).

## 6. Secrets

- No real secrets in source or compose defaults. Provide via `.env` (gitignored;
  see `.env.example`) or a secret manager. Rotate `app_user`, `GATEWAY_INTERNAL_API_KEY`,
  DB and WorkOS credentials before production.
- API-key secrets are shown once at issuance and only their sha256 hash is stored.

## 7. Rollout to enforced auth

1. Deploy with `MENDR_AUTH_ENFORCE=false` (current default). Tenant context binds
   from any credential present; nothing is rejected.
2. Ship the frontend WorkOS login (`REACT_APP_WORKOS_CLIENT_ID`), issue per-tenant
   edge keys, confirm all callers send credentials.
3. Flip `MENDR_AUTH_ENFORCE=true` in staging; verify 401 handling and edge sync;
   then promote to production.

## 8. Supply-chain / CI

`.github/workflows/security.yml` runs on push/PR + weekly: gitleaks (secrets),
Trivy (deps + IaC misconfig), pip-audit, npm audit, and CodeQL SAST for Java.

## 9. Reporting

Report suspected vulnerabilities privately to the security owner; do not open a
public issue with exploit details.

## 10. New service checklist (vet every call)

Any new HTTP-serving service MUST replicate the standard stack before it is added
to the mesh (copy from `rule-engine/security/*` — we intentionally duplicate
rather than share a library, so each service owns and can tune its own policy):

1. Add `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`.
2. Add `AuthProperties` (`mendr.auth.*`: `enforce`, `internal-api-key`,
   `cors-allowed-origins`, `workos.{jwks-uri,issuer,audience,org-claim}`).
3. Add `SecurityConfig`: stateless, CSRF off, CORS allow-list (never `*`),
   `permitAll` `/actuator/**` + `/error`, `authenticated()` on the service's API
   paths when `enforce=true`, `NimbusJwtDecoder` from the JWKS URI, then
   `InternalApiKeyAuthFilter` (before) and `TenantContextFilter` (after)
   `AuthorizationFilter`.
4. Add `InternalApiKeyAuthFilter`, `TenantContextFilter`, `TenantResolver`
   (org id -> tenant UUID via the `tenants` registry, cached).
5. Remove any `@CrossOrigin("*")`; lock `management.endpoints` to `health,info`.
6. Wire `MENDR_AUTH_*`, `GATEWAY_INTERNAL_API_KEY`, `MENDR_CORS_ALLOWED_ORIGINS`
   in `docker-compose.yml`.
7. Add filter tests: JWT `org_id` binds the mapped tenant; a valid internal key is
   trusted; a forged `X-Tenant-Id` without a principal is ignored.

Kafka-only services (no intended HTTP API, e.g. `notification-service`) instead
ship a `denyAll` `SecurityConfig` so they fail closed.
