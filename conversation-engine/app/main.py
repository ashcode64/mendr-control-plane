"""FastAPI entrypoint for the MendrScript conversation engine.

Exposes an SSE chat endpoint that runs the synthesis graph and streams progress
(propose -> verify -> simulate -> present) to the UI in the AI Analysis tab. The
final event carries the verified program, the verification result, and the
before/after simulation diff. There is intentionally no deploy endpoint.
"""
from __future__ import annotations

import json
import time
import uuid

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

from .config import settings
from .graph import build_graph
from .llm import Proposer
from .mcp_client import McpClient
from .security import screen_user_input

app = FastAPI(title="mendr-conversation-engine", version="1.0.0")
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)

_proposer = Proposer()
_mcp = McpClient()
_graph = build_graph(_proposer, _mcp)

# Minimal in-memory session store (message history + last program). For multi-replica
# deployments this would move to Redis; the engine holds no privileged state.
_sessions: dict[str, dict] = {}


class ChatRequest(BaseModel):
    sessionId: str | None = None
    message: str
    context: dict | None = None
    cases: list | None = None


@app.get("/health")
async def health():
    return {"status": "ok", "llm": _proposer.enabled, "mcp": settings.mcp_base_url}


def _touch_session(session_id: str | None) -> str:
    sid = session_id or str(uuid.uuid4())
    now = time.time()
    # opportunistic TTL sweep
    for k, v in list(_sessions.items()):
        if now - v.get("ts", now) > settings.session_ttl_seconds:
            _sessions.pop(k, None)
    _sessions.setdefault(sid, {"history": [], "ts": now})["ts"] = now
    return sid


@app.post("/chat/stream")
async def chat_stream(req: ChatRequest):
    sid = _touch_session(req.sessionId)
    flags = screen_user_input(req.message)

    async def event_gen():
        yield _sse("session", {"sessionId": sid})
        if flags:
            yield _sse("security", {"flags": flags})

        init = {
            "user_message": req.message,
            "context": req.context or {},
            "cases": req.cases or [],
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
            yield _sse("error", {"message": str(e)})
            return

        _sessions[sid]["history"].append({"role": "user", "text": req.message})
        _sessions[sid]["last_program"] = last.get("candidate")

        yield _sse("result", {
            "status": last.get("status"),
            "program": last.get("candidate"),
            "rationale": last.get("rationale"),
            "assistantText": last.get("assistant_text"),
            "verification": last.get("verification"),
            "simulation": last.get("simulation"),
            # Pinned for provenance/reproducibility (audit row stores this).
            "model": settings.anthropic_model,
            "securityFlags": flags,
            # The UI uses this to drive the existing approval flow; the engine itself
            # never deploys.
            "deployable": last.get("status") == "ready",
        })
        yield _sse("done", {"sessionId": sid})

    return EventSourceResponse(event_gen())


def _sse(event: str, data: dict) -> dict:
    return {"event": event, "data": json.dumps(data)}
