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
import uuid

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

from .auth import Principal, authenticate
from .analysis_client import AnalysisClient
from .config import settings
from .graph import build_graph
from .llm import Proposer
from .mcp_client import McpClient
from .ratelimit import SlidingWindowRateLimiter
from .security import screen_user_input, scrub_output

logger = logging.getLogger("mendr.conversation")
audit = logging.getLogger("mendr.conversation.audit")

PERSIST_ERROR_MSG = "conversation save failed — history may not be restored when you return"


@asynccontextmanager
async def lifespan(_app: FastAPI):
    if not settings.internal_api_key:
        logger.warning(
            "GATEWAY_INTERNAL_API_KEY is unset — MendrScript chat history will not "
            "persist correctly (conversation-engine cannot authenticate to "
            "ai-analysis-service internal APIs)"
        )
    if settings.llm_provider == "gemini":
        if settings.gemini_api_key:
            logger.info("LLM_PROVIDER=gemini active (model=%s)", settings.gemini_model)
        else:
            logger.warning("LLM_PROVIDER=gemini but GEMINI_API_KEY is not set — synthesis disabled")
    else:
        if settings.anthropic_api_key:
            logger.info("LLM_PROVIDER=anthropic active (model=%s)", settings.anthropic_model)
        else:
            logger.warning("LLM_PROVIDER=anthropic but ANTHROPIC_API_KEY is not set — synthesis disabled")
    yield


app = FastAPI(title="mendr-conversation-engine", version="1.0.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_allowed_origins,
    allow_credentials=True,
    allow_methods=["POST", "GET", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Api-Key", "X-Internal-Api-Key"],
)

_proposer = Proposer()
_mcp = McpClient()
_analysis = AnalysisClient()
_graph = build_graph(_proposer, _mcp)
_rate_limiter = SlidingWindowRateLimiter(settings.rate_limit_per_min, window_seconds=60.0)


class ChatRequest(BaseModel):
    analysisId: str | None = None
    sessionId: str | None = None
    message: str
    context: dict | None = None
    cases: list | None = None


@app.get("/health")
async def health():
    return {"status": "ok", "llm": _proposer.enabled, "mcp": settings.mcp_base_url}


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

    analysis_client = _analysis.for_tenant(tenant_id)
    conversation = None
    if req.analysisId:
        try:
            conversation = await analysis_client.get_conversation(req.analysisId, limit=10)
        except Exception as e:
            logger.warning("conversation lookup failed tenant=%s analysis=%s: %s", tenant_id, req.analysisId, e)
            raise HTTPException(status_code=502, detail="conversation lookup failed") from e

    sid = (conversation or {}).get("sessionId") or req.sessionId or str(uuid.uuid4())
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
            "prior_turns": [
                {"role": m.get("role"), "text": m.get("content")}
                for m in ((conversation or {}).get("messages") or [])
            ],
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
            persisted = False
            if req.analysisId:
                try:
                    await analysis_client.append_messages(req.analysisId, [
                        {"role": "user", "content": req.message},
                        {"role": "assistant", "content": "Error: synthesis failed", "metadata": {"error": True}},
                    ])
                    persisted = True
                except Exception as persist_err:
                    logger.warning("failed to persist synthesis error for analysis=%s: %s",
                                   req.analysisId, persist_err)
            yield _sse("error", {
                "message": "synthesis failed",
                "persisted": persisted,
                "persistError": None if persisted else PERSIST_ERROR_MSG,
            })
            return

        status = last.get("status")
        deployable = status == "ready"
        assistant_text = scrub_output(last.get("assistant_text"))
        result_payload = {
            "status": status,
            "program": last.get("candidate"),
            "rationale": scrub_output(last.get("rationale")),
            "assistantText": assistant_text,
            "verification": last.get("verification"),
            "simulation": last.get("simulation"),
            "model": settings.active_llm_model,
            "securityFlags": flags,
            "deployable": deployable,
            "persisted": True,
        }

        if req.analysisId:
            try:
                await analysis_client.append_messages(
                    req.analysisId,
                    [
                        {"role": "user", "content": req.message},
                        {
                            "role": "assistant",
                            "content": assistant_text or "Verified program proposed.",
                            "metadata": {
                                "status": status,
                                "model": settings.active_llm_model,
                                "verified": bool((last.get("verification") or {}).get("valid")),
                                "deployable": deployable,
                                "securityFlags": flags,
                            },
                        },
                    ],
                    last_result=result_payload,
                )
            except Exception as persist_err:
                logger.warning("failed to persist conversation turn tenant=%s analysis=%s: %s",
                               tenant_id, req.analysisId, persist_err)
                result_payload["persisted"] = False
                result_payload["persistError"] = PERSIST_ERROR_MSG
                yield _sse("persist_error", {
                    "message": PERSIST_ERROR_MSG,
                    "persisted": False,
                })

        audit.info(
            "chat.result tenant=%s session=%s status=%s verified=%s deployable=%s model=%s",
            tenant_id, sid, status,
            (last.get("verification") or {}).get("valid"), deployable, settings.active_llm_model,
        )

        yield _sse("result", result_payload)
        yield _sse("done", {"sessionId": sid})

    return EventSourceResponse(event_gen())


def _sse(event: str, data: dict) -> dict:
    return {"event": event, "data": json.dumps(data)}
