"""LangGraph synthesis loop: load_context -> propose -> verify -> (simulate | refine) -> present.

The graph is the orchestration spine. It has NO deploy node by construction — the
terminal `present` node hands the verified program + simulation back to the operator,
who approves through the existing control-plane flow.
"""
from __future__ import annotations

from typing import Optional, TypedDict

from langgraph.graph import END, StateGraph

from .config import settings
from .llm import Proposer
from .mcp_client import McpClient


class GraphState(TypedDict, total=False):
    user_message: str
    context: dict
    cases: list
    tenant_id: Optional[str]
    candidate: Optional[dict]
    rationale: str
    assistant_text: str
    verification: Optional[dict]
    simulation: Optional[dict]
    iterations: int
    prior_errors: list
    status: str
    notes: list


def build_graph(proposer: Proposer, mcp: McpClient):
    async def load_context(state: GraphState) -> dict:
        ctx = state.get("context") or {}
        cases = list(state.get("cases") or [])
        tmcp = mcp.for_tenant(state.get("tenant_id"))
        # Best-effort: pull a registered example payload to drive simulation.
        svc, ep = ctx.get("service"), ctx.get("endpoint")
        if svc and ep and not cases:
            try:
                contract = await tmcp.get_contract(svc, ep, ctx.get("direction", "REQUEST"))
                for ex in (contract.get("examples") or [])[:3]:
                    payload = ex.get("payload")
                    if isinstance(payload, dict):
                        cases.append({"input": payload})
            except Exception:
                pass
        return {"cases": cases, "iterations": 0, "prior_errors": [], "notes": []}

    async def propose(state: GraphState) -> dict:
        program, text = await proposer.propose(
            state["user_message"], state.get("context") or {}, state.get("prior_errors") or [])
        return {
            "candidate": program,
            "rationale": (program or {}).get("rationale", ""),
            "assistant_text": text,
            "iterations": state.get("iterations", 0) + 1,
        }

    async def verify(state: GraphState) -> dict:
        v = await mcp.for_tenant(state.get("tenant_id")).verify_program(state["candidate"])
        # Capture counterexamples HERE (a node return), not in the routing function —
        # LangGraph only persists state from node returns, so stashing prior_errors in
        # the conditional edge would be lost and the refine loop would re-propose blind.
        errors = [] if v.get("valid") else (v.get("errors") or ["verification failed"])
        return {"verification": v, "prior_errors": errors}

    async def simulate(state: GraphState) -> dict:
        report = await mcp.for_tenant(state.get("tenant_id")).simulate_transform(
            state["candidate"], state.get("cases") or [])
        return {"simulation": report, "status": "ready"}

    async def present(state: GraphState) -> dict:
        if not state.get("candidate"):
            return {"status": "no_program"}
        if not (state.get("verification") or {}).get("valid"):
            return {"status": "unverifiable"}
        return {"status": state.get("status", "ready")}

    def after_propose(state: GraphState) -> str:
        return "verify" if state.get("candidate") else "present"

    def after_verify(state: GraphState) -> str:
        # Pure routing only. prior_errors is already set by the verify node above.
        if (state.get("verification") or {}).get("valid"):
            return "simulate"
        if state.get("iterations", 0) < settings.max_refine_iterations:
            return "propose"
        return "present"

    g = StateGraph(GraphState)
    g.add_node("load_context", load_context)
    g.add_node("propose", propose)
    g.add_node("verify", verify)
    g.add_node("simulate", simulate)
    g.add_node("present", present)

    g.set_entry_point("load_context")
    g.add_edge("load_context", "propose")
    g.add_conditional_edges("propose", after_propose, {"verify": "verify", "present": "present"})
    g.add_conditional_edges("verify", after_verify,
                            {"simulate": "simulate", "propose": "propose", "present": "present"})
    g.add_edge("simulate", "present")
    g.add_edge("present", END)
    return g.compile()
