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

- Immutable system prompt (never templated with user input).
- User text and fetched context are treated as data, never instructions.
- Input action-screening guardrail flags prompt-injection / deploy attempts.
- No privileged capability: all actions go through MCP tools; the Java verifier is the
  authority and runs again at deploy time.
