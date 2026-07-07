# Conversation Engine (MendrScript Synthesizer)

FastAPI + LangGraph service behind the **AI Analysis** chat. It turns a natural-language
transformation request into a **verified, deterministic MendrScript program** and streams
the result to the UI over SSE.

## What it does (and does not)

- **Synthesizes** a closed-opcode AST via constrained tool-use (`propose_program`).
- **Verifies** every candidate via the `verify_program` MCP tool (the authoritative
  Java `MendrScriptVerifier` in api-gateway), looping to refine on failure.
- **Simulates** the program against contract example payloads (`simulate_transform`) to
  show before/after and surface fail-closed faults as counterexamples.
- **Does NOT deploy.** The terminal `present` node returns the verified program for the
  operator to approve through the existing control-plane flow
  (`api.transformations.approved` → rule-engine → gateway), which re-verifies server-side.

## Graph

```
load_context → propose → verify → (valid ? simulate : refine→propose, bounded) → present
```

## Run

```bash
pip install -r requirements.txt
MCP_BASE_URL=http://localhost:8082 ANTHROPIC_API_KEY=... \
  uvicorn app.main:app --port 8085
```

`POST /chat/stream` (SSE) body:

```json
{
  "sessionId": "optional",
  "message": "cents → dollars on /amount",
  "context": {"service": "payment-service", "endpoint": "/charge", "direction": "REQUEST"}
}
```

Events: `session`, `security`, `progress`, `result`, `done` (or `error`).

## Security

- **AuthN + tenancy:** `/chat/stream` authenticates the caller — a WorkOS JWT
  (validated against `MENDR_AUTH_WORKOS_JWKS_URI`, tenant = the `org_id` claim) or
  the shared `GATEWAY_INTERNAL_API_KEY` for machine callers. The resolved tenant is
  bound to the request and forwarded to the MCP surface (`X-Tenant-Id`) so
  RLS-scoped reads are correct. `MENDR_AUTH_ENFORCE=false` (default) keeps the
  endpoint open for incremental rollout but still binds the tenant.
- **CORS:** locked to `MENDR_CORS_ALLOWED_ORIGINS` (never `*`).
- **Isolation:** in-memory sessions are namespaced by tenant.
- **Abuse/cost controls (OWASP LLM10):** per-tenant + per-client rate limiting
  (`MENDR_CHAT_RATE_LIMIT_PER_MIN`), bounded message/context size, bounded refine
  iterations and `max_tokens`.
- **Injection/leak controls (OWASP LLM01/LLM02):** input action-screening flags
  prompt-injection / deploy attempts; assistant output is scrubbed of anything that
  looks like a credential before it leaves the service.
- Immutable system prompt (never templated with user input); user text and fetched
  context are treated as data, never instructions.
- **No privileged capability:** all actions go through MCP tools; the Java verifier
  is the authority and runs again at deploy time (OWASP LLM06: complete mediation +
  human-in-the-loop approval gate outside the model).

### Environment

| Env var | Default | Purpose |
|---|---|---|
| `MENDR_AUTH_ENFORCE` | `false` | Require a valid credential on `/chat/stream`. |
| `MENDR_AUTH_WORKOS_JWKS_URI` | _(unset)_ | WorkOS JWKS endpoint; enables JWT validation. |
| `MENDR_AUTH_WORKOS_ISSUER` / `_AUDIENCE` | _(unset)_ | JWT issuer / audience checks. |
| `MENDR_AUTH_WORKOS_ORG_CLAIM` | `org_id` | JWT claim mapped to the tenant. |
| `GATEWAY_INTERNAL_API_KEY` | _(unset)_ | Shared internal key (machine callers + MCP auth). |
| `MENDR_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated allowed origins. |
| `MENDR_CHAT_RATE_LIMIT_PER_MIN` | `20` | Per-tenant+client request cap per minute. |
| `MENDR_CHAT_MAX_MESSAGE_CHARS` | `8000` | Max user message size. |
| `MENDR_TENANCY_DEFAULT_TENANT_ID` | `00000000-…-0001` | Default tenant for fallback. |
