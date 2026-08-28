"""LangGraph synthesis loop: load_context -> propose -> verify -> (simulate | refine)
-> run_property_checks -> minimize -> present.

The graph is the orchestration spine. It has NO deploy node by construction — the
terminal `present` node hands the verified (and minimized) program + simulation back
to the operator, who approves through the existing control-plane flow.

LangGraph rule (>=0.2): node names must not match GraphState keys. Nodes = verbs;
state keys = nouns (e.g. verify -> verification, run_property_checks -> metamorphic).
"""
from __future__ import annotations

from typing import Optional, TypedDict

from langgraph.graph import END, StateGraph

from .config import settings
from .llm import Proposer
from .mcp_client import McpClient
from .minimize_helpers import (
    apply_citation_scrub as _apply_citation_scrub,
    declared_field_types as _declared_field_types,
    explicit_triggering_payload as _explicit_triggering_payload,
    merge_minimized_candidate as _merge_minimized_candidate,
    spec_trust as _spec_trust,
    unresolvable_paths as _unresolvable_paths,
)


class GraphState(TypedDict, total=False):
    user_message: str
    context: dict
    cases: list
    prior_turns: list
    tenant_id: Optional[str]
    candidate: Optional[dict]
    rationale: str
    assistant_text: str
    verification: Optional[dict]
    simulation: Optional[dict]
    metamorphic: Optional[dict]
    minimization: Optional[dict]
    iterations: int
    prior_errors: list
    status: str
    notes: list
    bandit: Optional[dict]
    ddmin: Optional[dict]


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
            state["user_message"],
            state.get("context") or {},
            state.get("prior_errors") or [],
            state.get("prior_turns") or [],
        )
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
        # Synthesis-only: reject ops outside structural sketch allowlist when present
        sketch = (state.get("context") or {}).get("sketch") or {}
        allowed = sketch.get("allowedOpcodes") or []
        if allowed and state.get("candidate") and v.get("valid"):
            ops = (state.get("candidate") or {}).get("ops") or []
            out_of_sketch = []
            for op in ops:
                if not isinstance(op, dict):
                    continue
                opcode = str(op.get("op") or op.get("opcode") or "").lower()
                if opcode and opcode not in {a.lower() for a in allowed}:
                    out_of_sketch.append(opcode)
            if out_of_sketch:
                errors = list(errors) + [f"out-of-sketch opcode(s): {out_of_sketch}"]
                v = dict(v)
                v["valid"] = False
                v["errors"] = errors
        return {"verification": v, "prior_errors": errors}

    async def simulate(state: GraphState) -> dict:
        report = await mcp.for_tenant(state.get("tenant_id")).simulate_transform(
            state["candidate"], state.get("cases") or [])
        return {"simulation": report}

    async def run_property_checks(state: GraphState) -> dict:
        """Phase 8.2 — offline property checks after verify/simulate; feeds SafetyScore."""
        inputs = []
        for c in state.get("cases") or []:
            if isinstance(c, dict) and c.get("input") is not None:
                inputs.append(c["input"])
        report = await mcp.for_tenant(state.get("tenant_id")).verify_properties(
            state["candidate"], inputs)
        status = "ready"
        if report and report.get("allPassed") is False:
            # Soft: still present, but passRate travels to Safety Gate via diagnose response
            pass
        return {"metamorphic": report, "status": status}

    async def minimize(state: GraphState) -> dict:
        """Remediation minimization — after critics, before present/approval."""
        cand = state.get("candidate")
        if not cand or not (state.get("verification") or {}).get("valid"):
            return {}
        ctx = state.get("context") or {}
        sketch = ctx.get("sketch") or {}
        sig = ctx.get("errorSignature") or ctx.get("error_signature") or {}
        if not isinstance(sig, dict):
            sig = {}
        triggering = _explicit_triggering_payload(ctx, sig)
        spec_trust = _spec_trust(ctx, sig)
        try:
            report = await mcp.for_tenant(state.get("tenant_id")).minimize_program(
                program=cand,
                cases=state.get("cases") or [],
                triggering_payload=triggering,
                spec_trust=spec_trust,
                allowed_opcodes=sketch.get("allowedOpcodes"),
                declared_field_types=_declared_field_types(ctx, sketch, sig),
                unresolvable_paths=_unresolvable_paths(ctx, sketch, sig),
            )
        except Exception as e:
            return {
                "minimization": {
                    "error": str(e),
                    "minimized": False,
                    "fellBack": True,
                    "engine": "minimize_unreachable",
                },
                "notes": list(state.get("notes") or []) + ["minimize_soft_fail"],
            }
        merged = _merge_minimized_candidate(cand, report)
        out: dict = {"candidate": merged, "minimization": report}
        if report.get("minimized") and merged is not None and merged is not cand:
            scrub = _apply_citation_scrub(
                cand, merged, state.get("rationale"), state.get("assistant_text")
            )
            if scrub.get("rationale") is not None:
                out["rationale"] = scrub["rationale"]
                if isinstance(merged, dict):
                    merged = dict(merged)
                    merged["rationale"] = scrub["rationale"]
                    out["candidate"] = merged
            if scrub.get("assistant_text") is not None:
                out["assistant_text"] = scrub["assistant_text"]
            if isinstance(out.get("minimization"), dict):
                mm = dict(out["minimization"])
                mm["droppedPaths"] = scrub.get("droppedPaths") or []
                out["minimization"] = mm
        return out

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
    g.add_node("run_property_checks", run_property_checks)
    g.add_node("minimize", minimize)
    g.add_node("present", present)

    g.set_entry_point("load_context")
    g.add_edge("load_context", "propose")
    g.add_conditional_edges("propose", after_propose, {"verify": "verify", "present": "present"})
    g.add_conditional_edges("verify", after_verify,
                            {"simulate": "simulate", "propose": "propose", "present": "present"})
    g.add_edge("simulate", "run_property_checks")
    g.add_edge("run_property_checks", "minimize")
    g.add_edge("minimize", "present")
    g.add_edge("present", END)
    return g.compile()
