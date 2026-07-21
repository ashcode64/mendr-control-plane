"""Internal diagnosis entry: runs the LangGraph loop on an ErrorSignature.

Complexity-gated:
  - deterministicDiff → Synthesis only (propose→verify→simulate)
  - UNKNOWN / multi-hop → Diagnostic context enrichment then Synthesis
Critics remain MCP verify_program + simulate_transform (deterministic).
"""
from __future__ import annotations

import logging
from typing import Any

from .config import settings
from .graph import build_graph
from .llm import Proposer
from .mcp_client import McpClient

logger = logging.getLogger("mendr.diagnose")

_proposer = Proposer()
_mcp = McpClient()
_graph = build_graph(_proposer, _mcp)


def _should_diagnose_first(complexity: dict | None, signature: dict) -> bool:
    complexity = complexity or {}
    if complexity.get("deterministicDiff"):
        return False
    category = (complexity.get("category") or signature.get("category") or "").upper()
    if category in ("UNKNOWN", ""):
        return True
    if complexity.get("multiHop"):
        return True
    if not signature.get("change_type"):
        return True
    return False


def _has_structured_problem(sig: dict, complexity: dict | None) -> bool:
    """Path A1: Problem Details (or signature slots) already disambiguated the error."""
    complexity = complexity or {}
    if complexity.get("hasProblemJson") or sig.get("problemDetail"):
        pd = sig.get("problemDetail") if isinstance(sig.get("problemDetail"), dict) else {}
        ext = pd.get("extensions") if isinstance(pd.get("extensions"), dict) else {}
        if ext.get("json_path") or ext.get("template_id") or ext.get("errors"):
            return True
        if pd.get("type") and "problem" in str(pd.get("type", "")).lower():
            # Explicit problem+json type URI without free-text-only body
            if sig.get("json_path") or sig.get("template_id"):
                return True
    if sig.get("json_path") and sig.get("change_type"):
        return True
    return False


async def run_diagnose(
    error_signature: dict,
    cases: list | None = None,
    complexity: dict | None = None,
    tenant_id: str | None = None,
    prior_turns: list | None = None,
) -> dict[str, Any]:
    """Run synthesis against an ErrorSignature; return program + verification + simulation."""
    sig = error_signature or {}
    cases = list(cases or [])
    tenant_id = tenant_id or settings.default_tenant_id

    # Optional Drain3 pass for UNKNOWN opaque strings.
    # Path A1: skip mining when Problem Details already carry structured extensions
    # (template_id / json_path / errors) — Drain cannot improve on that.
    template_meta: dict = {}
    raw = sig.get("raw_excerpt") or ""
    category = (sig.get("category") or "").upper()
    has_problem_json = _has_structured_problem(sig, complexity)
    if (
        category in ("UNKNOWN", "")
        and raw
        and not sig.get("template_id")
        and not has_problem_json
    ):
        try:
            from .template_miner import TemplateMiner
            miner = TemplateMiner()
            mined = miner.mine(raw)
            if mined:
                sig = dict(sig)
                sig["template_id"] = mined.template_id
                template_meta = {
                    "skeleton": mined.skeleton,
                    "variables": mined.variables,
                    "is_new": mined.is_new,
                    "low_similarity": mined.low_similarity,
                }
        except Exception as e:
            logger.debug("template mine skipped: %s", e)

    # Fetch taxonomy + precedents when diagnostic path
    diagnosis: dict | None = None
    tmcp = _mcp.for_tenant(tenant_id)
    if _should_diagnose_first(complexity, sig):
        diagnosis = {"hypothesis": None, "precedents": [], "taxonomy": None}
        try:
            if sig.get("failureId"):
                es = await tmcp.call_tool("get_error_signature", {"failureId": sig["failureId"]})
                diagnosis["taxonomy"] = (es or {}).get("taxonomy")
                if (es or {}).get("errorSignature"):
                    # merge richer server signature
                    merged = dict(es["errorSignature"])
                    merged.update({k: v for k, v in sig.items() if v is not None})
                    sig = merged
            prec = await tmcp.call_tool("get_precedents", {
                "failureId": sig.get("failureId"),
                "sourceService": (
                    sig.get("sourceService")
                    or (sig.get("contract_coords") or {}).get("sourceService")
                    or (sig.get("contract_coords") or {}).get("source")
                ),
                "targetService": (sig.get("contract_coords") or {}).get("service"),
                "endpoint": (sig.get("contract_coords") or {}).get("endpoint"),
                "changeType": sig.get("change_type"),
                "jsonPath": sig.get("json_path"),
                "includeNegatives": True,  # dual-outcome RAG: SUCCESS + FAILURE warn-offs
            })
            diagnosis["precedents"] = (prec or {}).get("precedents") or []
            diagnosis["graphNeighbors"] = (prec or {}).get("graphNeighbors") or []
            diagnosis["causalHints"] = (prec or {}).get("causalHints") or []
            diagnosis["globalDrift"] = (prec or {}).get("globalDrift") or []
            diagnosis["owner_action_required"] = bool((prec or {}).get("owner_action_required"))
            diagnosis["refuseAutoHeal"] = bool((prec or {}).get("refuseAutoHeal"))
            diagnosis["lagReason"] = (prec or {}).get("lagReason")
            diagnosis["lagEvidence"] = (prec or {}).get("lagEvidence") or []
            diagnosis["retrieval"] = (prec or {}).get("retrieval")
            diagnosis["hypothesis"] = _build_hypothesis(sig, diagnosis)
        except Exception as e:
            logger.warning("diagnostic enrichment failed: %s", e)
            diagnosis["error"] = str(e)

    # CEGIS sketch hint from localized signature (structural sketch-with-holes)
    sketch = {
        "kind": "structural_sketch_with_holes",
        "change_type": sig.get("change_type"),
        "json_path": sig.get("json_path"),
        "expected_type": sig.get("expected_type"),
        "observed_type": sig.get("observed_type"),
        "hole": None,
        "holes": [],
        "allowedOpcodes": _allowed_opcodes(sig.get("change_type")),
    }
    if sig.get("change_type"):
        hole_token = f"<HOLE:{str(sig['change_type']).lower()}"
        if sig.get("json_path"):
            hole_token += f" {sig['json_path']}"
        hole_token += ">"
        sketch["hole"] = hole_token
        sketch["holes"] = [{
            "change_type": sig.get("change_type"),
            "json_path": sig.get("json_path"),
            "expected_type": sig.get("expected_type"),
            "observed_type": sig.get("observed_type"),
            "token": hole_token,
            "allowedOpcodes": sketch["allowedOpcodes"],
        }]

    # Phase 8.3a: multi-field ddmin when N>1 and no precise single pointer
    ddmin_meta: dict | None = None
    refuse_ddmin = False
    drifted = (complexity or {}).get("driftedFields") or sig.get("driftedFields") or []
    if isinstance(drifted, list) and len(drifted) > 1 and not _has_structured_problem(sig, complexity):
        try:
            coords = sig.get("contract_coords") or {}
            payload = None
            if cases:
                first = cases[0]
                if isinstance(first, dict):
                    payload = first.get("input") or first.get("payload")
            ddmin_meta = await tmcp.localize_fields(
                category=sig.get("category") or category,
                httpMethod=(complexity or {}).get("httpMethod") or sig.get("httpMethod"),
                jsonPath=sig.get("json_path"),
                fields=drifted,
                payload=payload,
                targetService=coords.get("service") or coords.get("targetService"),
                endpoint=coords.get("endpoint"),
                baseUrl=sig.get("registeredBaseUrl") or coords.get("baseUrl"),
            )
            if ddmin_meta and ddmin_meta.get("aborted"):
                refuse_ddmin = True
            elif ddmin_meta and ddmin_meta.get("minimal"):
                # Rebuild sketch from minimal set
                holes = []
                for f in ddmin_meta["minimal"]:
                    if not isinstance(f, dict):
                        continue
                    ct = f.get("change_type") or sig.get("change_type")
                    jp = f.get("json_path")
                    token = f"<HOLE:{str(ct).lower()} {jp}>" if ct else None
                    holes.append({
                        "change_type": ct,
                        "json_path": jp,
                        "expected_type": f.get("expected_type"),
                        "observed_type": f.get("observed_type"),
                        "token": token,
                        "allowedOpcodes": _allowed_opcodes(ct),
                    })
                if holes:
                    sketch["holes"] = holes
                    sketch["hole"] = holes[0].get("token")
                    sketch["allowedOpcodes"] = holes[0].get("allowedOpcodes") or []
                    if holes[0].get("json_path"):
                        sig = dict(sig)
                        sig["json_path"] = holes[0]["json_path"]
                        if holes[0].get("change_type"):
                            sig["change_type"] = holes[0]["change_type"]
                        if holes[0].get("expected_type"):
                            sig["expected_type"] = holes[0]["expected_type"]
        except Exception as e:
            logger.debug("ddmin skipped: %s", e)

    # 8.3b Deterministic fast-path: single implied opcode → materialize without LLM
    deterministic_program = _try_materialize(sketch)
    ambiguous = _should_diagnose_first(complexity, sig) and deterministic_program is None

    # 8.3c Hierarchical bandit — only ambiguous / agent-loop cases
    bandit_meta: dict | None = None
    if ambiguous:
        try:
            preferred = []
            for hole in sketch.get("holes") or []:
                if isinstance(hole, dict) and hole.get("change_type"):
                    preferred.append(_bandit_category(hole["change_type"]))
            if sig.get("change_type"):
                preferred.append(_bandit_category(sig["change_type"]))
            bandit_meta = await tmcp.call_tool("select_bandit_arms", {
                "ambiguous": True,
                "preferredCategories": list(dict.fromkeys(preferred)),
            })
        except Exception as e:
            logger.debug("bandit select skipped: %s", e)

    user_message = _propose_prompt(sig, sketch, diagnosis, bandit_meta)
    context = {
        "errorSignature": sig,
        "sketch": sketch,
        "diagnosis": diagnosis,
        "templateMeta": template_meta,
        "bandit": bandit_meta,
        "service": (sig.get("contract_coords") or {}).get("service"),
        "endpoint": (sig.get("contract_coords") or {}).get("endpoint"),
        "direction": (sig.get("contract_coords") or {}).get("direction", "REQUEST"),
    }

    # Path C: abort ddmin → HITL without synthesis / live probes
    if refuse_ddmin:
        return {
            "status": "unverifiable",
            "program": None,
            "rationale": (ddmin_meta or {}).get("abortReason") or "ddmin aborted for unsafe/mutating upstream",
            "assistantText": None,
            "verification": None,
            "simulation": None,
            "metamorphic": None,
            "ddmin": ddmin_meta,
            "bandit": bandit_meta,
            "model": settings.active_llm_model,
            "errorSignature": sig,
            "sketch": sketch,
            "diagnosis": diagnosis,
            "templateMeta": template_meta,
            "confidence": 0.35,
            "deployable": False,
            "refuseAutoHeal": True,
            "owner_action_required": True,
            "lagReason": (ddmin_meta or {}).get("abortReason"),
            "lagEvidence": list((diagnosis or {}).get("lagEvidence") or []) if diagnosis else [],
        }

    # Deterministic Synthesis-only: skip LLM propose
    if deterministic_program is not None:
        init = {
            "user_message": user_message,
            "context": context,
            "cases": cases,
            "prior_turns": prior_turns or [],
            "tenant_id": tenant_id,
            "candidate": deterministic_program,
            "rationale": "deterministic hole-fill (no LLM)",
            "iterations": 1,
            "bandit": bandit_meta,
            "ddmin": ddmin_meta,
        }
        # Jump into verify by seeding candidate then running graph from verify via full stream
        # with a pre-set candidate: load_context → propose would overwrite; instead verify directly.
        last = await _run_critics_only(deterministic_program, context, cases, tenant_id, bandit_meta, ddmin_meta)
    else:
        init = {
            "user_message": user_message,
            "context": context,
            "cases": cases,
            "prior_turns": prior_turns or [],
            "tenant_id": tenant_id,
            "bandit": bandit_meta,
            "ddmin": ddmin_meta,
        }
        last = {}
        async for chunk in _graph.astream(init, stream_mode="values"):
            last = chunk

    status = last.get("status") or "unverifiable"
    refuse = bool(diagnosis and (
        diagnosis.get("refuseAutoHeal") or diagnosis.get("owner_action_required")
    ))
    ready = status == "ready"
    return {
        "status": status,
        "program": last.get("candidate"),
        "rationale": last.get("rationale"),
        "assistantText": last.get("assistant_text"),
        "verification": last.get("verification"),
        "simulation": last.get("simulation"),
        "metamorphic": last.get("metamorphic"),
        "ddmin": ddmin_meta,
        "bandit": bandit_meta or last.get("bandit"),
        "model": settings.active_llm_model,
        "errorSignature": sig,
        "sketch": sketch,
        "diagnosis": diagnosis,
        "templateMeta": template_meta,
        "confidence": (0.4 if refuse else 0.9) if ready else 0.4,
        "deployable": ready and not refuse,
        "refuseAutoHeal": refuse,
        "owner_action_required": bool(diagnosis and diagnosis.get("owner_action_required")),
        "lagReason": (diagnosis or {}).get("lagReason") if diagnosis else None,
        "lagEvidence": list((diagnosis or {}).get("lagEvidence") or []) if diagnosis else [],
    }


async def _run_critics_only(
    program: dict,
    context: dict,
    cases: list,
    tenant_id: str | None,
    bandit_meta: dict | None,
    ddmin_meta: dict | None,
) -> dict:
    """Verify → simulate → metamorphic for a deterministically materialized program."""
    tmcp = _mcp.for_tenant(tenant_id)
    verification = await tmcp.verify_program(program)
    if not verification.get("valid"):
        return {
            "candidate": program,
            "verification": verification,
            "status": "unverifiable",
            "rationale": "deterministic program failed verify",
            "bandit": bandit_meta,
            "ddmin": ddmin_meta,
        }
    simulation = await tmcp.simulate_transform(program, cases or [])
    inputs = []
    for c in cases or []:
        if isinstance(c, dict) and c.get("input") is not None:
            inputs.append(c["input"])
    metamorphic = await tmcp.verify_properties(program, inputs)
    return {
        "candidate": program,
        "verification": verification,
        "simulation": simulation,
        "metamorphic": metamorphic,
        "status": "ready",
        "rationale": "deterministic hole-fill (no LLM)",
        "bandit": bandit_meta,
        "ddmin": ddmin_meta,
    }


def _try_materialize(sketch: dict) -> dict | None:
    """Single-hole deterministic materialize when exactly one opcode is implied."""
    holes = sketch.get("holes") or []
    if len(holes) != 1:
        return None
    hole = holes[0] if isinstance(holes[0], dict) else {}
    allowed = hole.get("allowedOpcodes") or sketch.get("allowedOpcodes") or []
    if len(allowed) != 1:
        return None
    opcode = str(allowed[0]).lower()
    path = hole.get("json_path") or sketch.get("json_path")
    if not path:
        return None
    op: dict[str, Any] = {"op": opcode, "path": path}
    if opcode == "coerce":
        op["targetType"] = hole.get("expected_type") or sketch.get("expected_type") or "string"
    elif opcode == "default":
        op["value"] = ""
        op["on"] = "missing"
    elif opcode in ("remove", "strip_unknown"):
        pass
    elif opcode in ("rename", "move", "copy"):
        return None  # needs from/to
    else:
        return None
    return {"schemaVersion": "1", "ops": [op]}


def _bandit_category(change_type: str) -> str:
    u = str(change_type).upper()
    if "COERCE" in u or "TYPE" in u:
        return "DATA_COERCION"
    if "DEFAULT" in u or "ADD" in u:
        return "ADD_DEFAULT"
    if "REMOVE" in u:
        return "FIELD_REMOVE"
    if "RESPONSE" in u:
        return "RESPONSE_MAP"
    if "ROUTING" in u:
        return "ROUTING"
    if "CORS" in u:
        return "CORS"
    return "STRUCTURAL_MAPPING"


def _allowed_opcodes(change_type: str | None) -> list[str]:
    if not change_type:
        return []
    u = str(change_type).upper()
    if "RENAME" in u:
        return ["rename", "move", "copy"]
    if "COERCE" in u or "TYPE" in u:
        return ["coerce", "map_value", "string_op"]
    if "DEFAULT" in u or "ADD" in u:
        return ["default", "coalesce"]
    if "REMOVE" in u:
        return ["remove", "strip_unknown"]
    if "WRAP" in u:
        return ["wrap", "wrap_array"]
    if "UNWRAP" in u:
        return ["unwrap", "unwrap_array"]
    return []


def _build_hypothesis(sig: dict, diagnosis: dict) -> str:
    parts = [
        f"category={sig.get('category')}",
        f"change_type={sig.get('change_type')}",
        f"json_path={sig.get('json_path')}",
        f"expected={sig.get('expected_type')} observed={sig.get('observed_type')}",
    ]
    tax = diagnosis.get("taxonomy") or {}
    if isinstance(tax, dict) and tax.get("found"):
        entry = tax.get("entry") or {}
        parts.append(f"taxonomy={entry.get('meaning')}; opcode={entry.get('suggested_opcode')}")
    neighbors = diagnosis.get("graphNeighbors") or []
    if neighbors:
        parts.append(f"topology_neighbors={len(neighbors)}")
    if diagnosis.get("owner_action_required"):
        parts.append("owner_action_required=true")
        if diagnosis.get("lagReason"):
            parts.append(f"lag={diagnosis['lagReason']}")
    if diagnosis.get("retrieval"):
        parts.append(f"retrieval={diagnosis['retrieval']}")
    return "; ".join(parts)


def _propose_prompt(sig: dict, sketch: dict, diagnosis: dict | None, bandit: dict | None = None) -> str:
    lines = [
        "Propose a minimal MendrScript program that fixes this ErrorSignature.",
        "Do NOT invent fields outside the signature. Prefer filling the sketch hole.",
        f"ErrorSignature: {sig}",
        f"Sketch: {sketch}",
    ]
    if diagnosis and diagnosis.get("hypothesis"):
        lines.append(f"DiagnosticHypothesis: {diagnosis['hypothesis']}")
    if diagnosis and diagnosis.get("precedents"):
        lines.append(f"PrecedentsCount: {len(diagnosis['precedents'])}")
        # Surface FAILURE warn-offs when dual-outcome RAG returned them
        fails = [p for p in diagnosis["precedents"] if isinstance(p, dict) and p.get("outcome") == "FAILURE"]
        if fails:
            lines.append(f"WarnOffs: avoid strategies that failed before ({len(fails)} FAILURE precedents)")
    if diagnosis and diagnosis.get("causalHints"):
        lines.append(f"CausalHints: {diagnosis['causalHints']}")
    if bandit and bandit.get("engaged") and bandit.get("arms"):
        cats = [a.get("category") for a in bandit["arms"] if isinstance(a, dict)]
        lines.append(f"BanditPreferredCategories: {cats}")
        if bandit.get("category"):
            lines.append(f"Prefer strategy category {bandit['category']} (Thompson-sampled).")
    if diagnosis and diagnosis.get("owner_action_required"):
        lines.append(
            "WARNING: owner_action_required — do not auto-heal a downstream victim; "
            "propose a diagnostic note / escalate instead of a speculative transform."
        )
        if diagnosis.get("lagReason"):
            lines.append(f"LagEvidence: {diagnosis['lagReason']}")
    return "\n".join(lines)
