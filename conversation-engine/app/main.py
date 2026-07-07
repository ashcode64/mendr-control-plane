"""FastAPI entrypoint for the MendrScript conversation engine.

Exposes an SSE chat endpoint that runs the synthesis graph and streams progress
(propose -> verify -> simulate -> present) to the UI in the AI Analysis tab. The
final event carries the verified program, the verification result, and the
before/after simulation diff. There is intentionally no deploy endpoint.

Security posture (defense-in-depth, all outside the model):
  - CORS locked to the configured dashboard origin(s) (never "*").
  - /chat/stream authenticates the caller (WorkOS JWT or internal key) and binds
    the resolved tenant so RLS-scoped MCP reads are correct.
  - Per-tenant session isolation; per-tenant + per-client rate limiting.
  - Input action-screening + output secret-scrubbing; bounded message size.
  - The engine holds NO deploy capability; the Java verifier re-runs at deploy.
"""
from __future__ import annotations

import json
import logging
import time
import uuid

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

from .auth import Principal, authenticate
from .config import settings
from .graph import build_graph
from .llm import Proposer
from .mcp_client import McpClient
from .ratelimit import SlidingWindowRateLimiter
from .security import screen_user_input, scrub_output

logger = logging.getLogger("mendr.conversation")
audit = logging.getLogger("mendr.conversation.audit")

app = FastAPI(title="mendr-conversation-engine", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_allowed_origins,
    allow_credentials=True,
    allow_methods=["POST", "GET", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Api-Key", "X-Internal-Api-Key"],
)

_proposer = Proposer()
_mcp = McpClient()
_graph = build_graph(_proposer, _mcp)
_rate_limiter = SlidingWindowRateLimiter(settings.rate_limit_per_min, window_seconds=60.0)

# Minimal in-memory session store, namespaced by tenant so one tenant can never
# read/continue another's session. For multi-replica deployments this would move to
# Redis; the engine holds no privileged state.
_sessions: dict[str, dict] = {}


class ChatRequest(BaseModel):
    sessionId: str | None = None
    message: str
    context: dict | None = None
    cases: list | None = None


@app.get("/health")
async def health():
    return {"status": "ok", "llm": _proposer.enabled, "mcp": settings.mcp_base_url}


def _session_key(tenant_id: str, session_id: str) -> str:
    return f"{tenant_id}:{session_id}"


def _touch_session(tenant_id: str, session_id: str | None) -> str:
    sid = session_id or str(uuid.uuid4())
    key = _session_key(tenant_id, sid)
    now = time.time()
    # opportunistic TTL sweep across all tenants
    for k, v in list(_sessions.items()):
        if now - v.get("ts", now) > settings.session_ttl_seconds:
            _sessions.pop(k, None)
    _sessions.setdefault(key, {"history": [], "ts": now})["ts"] = now
    return sid


@app.post("/chat/stream")
async def chat_stream(
    req: ChatRequest,
    request: Request,
    principal: Principal = Depends(authenticate),
):
    tenant_id = principal.tenant_id

    # ── Abuse / cost controls (fail fast, before any model call) ──────────────
    client_ip = request.client.host if request.client else "unknown"
    if not _rate_limiter.allow(f"{tenant_id}:{client_ip}"):
        raise HTTPException(status_code=429, detail="rate limit exceeded")

    if not req.message or not req.message.strip():
        raise HTTPException(status_code=400, detail="message is required")
    if len(req.message) > settings.max_message_chars:
        raise HTTPException(status_code=413, detail="message too large")
    if req.context and len(json.dumps(req.context)) > settings.max_context_chars:
        raise HTTPException(status_code=413, detail="context too large")

    sid = _touch_session(tenant_id, req.sessionId)
    flags = screen_user_input(req.message)

    audit.info(
        "chat.start tenant=%s principal=%s session=%s ip=%s flags=%s",
        tenant_id, principal.kind, sid, client_ip, flags,
    )

    async def event_gen():
        yield _sse("session", {"sessionId": sid})
        if flags:
            yield _sse("security", {"flags": flags})

        init = {
            "user_message": req.message,
            "context": req.context or {},
            "cases": req.cases or [],
            "tenant_id": tenant_id,
        }
        last: dict = {}
        try:
            async for chunk in _graph.astream(init, stream_mode="values"):
                last = chunk
                yield _sse("progress", {
                    "status": chunk.get("status"),
                    "iterations": chunk.get("iterations"),
                    "hasCandidate": chunk.get("candidate") is not None,
                    "verified": (chunk.get("verification") or {}).get("valid"),
                })
        except Exception as e:  # surface engine errors instead of hanging the stream
            logger.warning("graph stream failed tenant=%s session=%s: %s", tenant_id, sid, e)
            yield _sse("error", {"message": "synthesis failed"})
            return

        key = _session_key(tenant_id, sid)
        if key in _sessions:
            _sessions[key]["history"].append({"role": "user", "text": req.message})
            _sessions[key]["last_program"] = last.get("candidate")

        status = last.get("status")
        deployable = status == "ready"
        audit.info(
            "chat.result tenant=%s session=%s status=%s verified=%s deployable=%s model=%s",
            tenant_id, sid, status,
            (last.get("verification") or {}).get("valid"), deployable, settings.anthropic_model,
        )

        yield _sse("result", {
            "status": status,
            "program": last.get("candidate"),
            "rationale": scrub_output(last.get("rationale")),
            "assistantText": scrub_output(last.get("assistant_text")),
            "verification": last.get("verification"),
            "simulation": last.get("simulation"),
            # Pinned for provenance/reproducibility (audit row stores this).
            "model": settings.anthropic_model,
            "securityFlags": flags,
            # The UI uses this to drive the existing approval flow; the engine itself
            # never deploys.
            "deployable": deployable,
        })
        yield _sse("done", {"sessionId": sid})

    return EventSourceResponse(event_gen())


def _sse(event: str, data: dict) -> dict:
    return {"event": event, "data": json.dumps(data)}
