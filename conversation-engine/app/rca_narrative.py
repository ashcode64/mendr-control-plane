"""Zero-hallucination RCA narrative: enumerate → select → verify → cite → conformal → abstain.

The deterministic topology CTEs on ai-analysis (get_root_cause_candidates / get_blast_radius /
get_dependency_cycles) produce a CLOSED, enumerated set of real dependency paths (each with real
node/edge ids). The LLM is never asked to *generate* a causal path — it may only SELECT a
`pathIndex` from that set and assert claims that reference ids already in the set. Every claim is
then symbolically re-checked against the live graph via `verify_rca_claims`; if any claim is
unsupported, the citation lint fails, confidence is too low, or faithfulness < 1 - alpha, the
whole narrative ABSTAINS rather than emit an unverifiable sentence.

This module is additive telemetry: it never gates a heal. It is OFF unless
`MENDR_RCA_NARRATIVE_ENABLED=true`. With no LLM configured it abstains (the safe default).
"""
from __future__ import annotations

import json
import logging
from typing import Any

from .config import settings

logger = logging.getLogger("mendr.rca")


# ── Strict tool-use schema: the model SELECTS from the enumerated set, never invents ──

_SELECT_TOOL_NAME = "select_root_cause_path"
_SELECT_TOOL_DESCRIPTION = (
    "Select the single most likely root-cause dependency path for the failing service FROM THE "
    "ENUMERATED CANDIDATE PATHS you were given. You may ONLY reference pathIndex values and edge/node "
    "ids that appear in that closed set. Do NOT invent services, edges, ids, or paths. Every causal "
    "assertion in `narrative` MUST correspond to an entry in `claims` that cites a real id."
)


def _select_tool_input_schema() -> dict:
    return {
        "type": "object",
        "properties": {
            "pathIndex": {
                "type": "integer",
                "description": "Index of the chosen path in the enumerated candidate set.",
            },
            "rootCauseService": {
                "type": "string",
                "description": "The service in the chosen path you believe is the root cause "
                               "(must be one of that path's services).",
            },
            "claims": {
                "type": "array",
                "description": "Every topology fact the narrative relies on, each citing a real id.",
                "items": {
                    "type": "object",
                    "properties": {
                        "type": {"type": "string", "enum": ["edge", "node", "causal"]},
                        "edgeId": {"type": "integer"},
                        "nodeId": {"type": "integer"},
                        "sourceService": {"type": "string"},
                        "targetService": {"type": "string"},
                    },
                },
            },
            "narrative": {
                "type": "string",
                "description": "2-4 sentence root-cause explanation grounded ONLY in the chosen path.",
            },
            "confidence": {
                "type": "number",
                "description": "Your calibrated confidence in this selection, 0..1.",
            },
        },
        "required": ["pathIndex", "rootCauseService", "claims", "narrative", "confidence"],
    }


# ── Enumerated ground-truth set (built from the deterministic topology tools) ──


def build_enumerated_set(candidates: dict, blast: dict | None, cycles: dict | None) -> dict:
    """Normalize the topology tool outputs into the closed set the model may select from.

    Returns allowed id/service sets plus the presentable path list. Pure — unit-testable.
    """
    paths = [p for p in (candidates or {}).get("paths", []) if isinstance(p, dict)]
    allowed_edge_ids: set[int] = set()
    allowed_node_ids: set[int] = set()
    allowed_services: set[str] = set()

    for p in paths:
        for e in p.get("edgeIds") or []:
            _add_int(allowed_edge_ids, e)
        for n in p.get("nodeIds") or []:
            _add_int(allowed_node_ids, n)
        for s in p.get("services") or []:
            if isinstance(s, str):
                allowed_services.add(s.lower())

    for dep in (candidates or {}).get("dependencies", []) or []:
        if isinstance(dep, dict) and isinstance(dep.get("service"), str):
            allowed_services.add(dep["service"].lower())

    affected = []
    for a in ((blast or {}).get("affected") or []):
        if isinstance(a, dict) and isinstance(a.get("service"), str):
            allowed_services.add(a["service"].lower())
            affected.append(a)

    return {
        "paths": paths,
        "allowedEdgeIds": allowed_edge_ids,
        "allowedNodeIds": allowed_node_ids,
        "allowedServices": allowed_services,
        "affected": affected,
        "cycles": (cycles or {}).get("cycles") or [],
    }


# ── Citation linter (pure) ──


def lint_selection(selection: dict, enumerated: dict) -> tuple[bool, list[str]]:
    """Reject any selection that references a path/id/service outside the enumerated closed set.

    This is the structural gate BEFORE the symbolic verifier: it makes fabricated ids/paths
    impossible to smuggle through, independent of what the DB check later confirms.
    """
    violations: list[str] = []
    if not isinstance(selection, dict):
        return False, ["selection is not an object"]

    paths = enumerated.get("paths") or []
    idx = selection.get("pathIndex")
    if not isinstance(idx, int) or idx < 0 or idx >= len(paths):
        return False, [f"pathIndex {idx!r} is not one of the {len(paths)} enumerated paths"]

    chosen = paths[idx]
    chosen_services = {s.lower() for s in (chosen.get("services") or []) if isinstance(s, str)}
    chosen_edge_ids = {int(e) for e in (chosen.get("edgeIds") or []) if _is_int(e)}

    root = selection.get("rootCauseService")
    if not isinstance(root, str) or root.lower() not in chosen_services:
        violations.append(f"rootCauseService {root!r} is not in the chosen path's services")

    allowed_edges = enumerated.get("allowedEdgeIds") or set()
    allowed_nodes = enumerated.get("allowedNodeIds") or set()
    allowed_services = enumerated.get("allowedServices") or set()

    claims = selection.get("claims")
    if not isinstance(claims, list) or not claims:
        violations.append("no claims provided")
        claims = []

    references_chosen_path = False
    for i, c in enumerate(claims):
        if not isinstance(c, dict):
            violations.append(f"claim[{i}] is not an object")
            continue
        eid, nid = c.get("edgeId"), c.get("nodeId")
        if eid is not None:
            if not _is_int(eid) or int(eid) not in allowed_edges:
                violations.append(f"claim[{i}].edgeId {eid!r} not in enumerated edge ids")
            elif int(eid) in chosen_edge_ids:
                references_chosen_path = True
        if nid is not None:
            if not _is_int(nid) or int(nid) not in allowed_nodes:
                violations.append(f"claim[{i}].nodeId {nid!r} not in enumerated node ids")
        for svc_key in ("sourceService", "targetService"):
            svc = c.get(svc_key)
            if svc is not None and (not isinstance(svc, str) or svc.lower() not in allowed_services):
                violations.append(f"claim[{i}].{svc_key} {svc!r} not a known topology service")

    if not references_chosen_path:
        violations.append("no claim cites an edge of the chosen path (narrative is ungrounded)")

    return (len(violations) == 0), violations


# ── Orchestration ──


async def run_rca_narrative(
    error_signature: dict,
    tmcp: Any,
    tenant_id: str | None = None,
    service: str | None = None,
) -> dict:
    """Produce a verified, cited RCA narrative for the failing service, or a principled abstention.

    `tmcp` is a tenant-bound McpClient (from `McpClient.for_tenant`). Never raises: any failure
    degrades to an abstention so it can be attached to a diagnosis result without risk.
    """
    if not settings.rca_narrative_enabled:
        return {"enabled": False}

    sig = error_signature or {}
    coords = sig.get("contract_coords") if isinstance(sig.get("contract_coords"), dict) else {}
    failing = service or coords.get("service") or coords.get("targetService") or sig.get("targetService")
    if not failing:
        return _abstain("no failing service resolved from signature", enabled=True)

    depth = max(1, settings.rca_max_depth)
    max_paths = max(1, settings.rca_max_paths)

    try:
        candidates = await tmcp.call_tool(
            "get_root_cause_candidates", {"service": failing, "maxDepth": depth}
        )
    except Exception as e:  # abstain, never break the caller
        logger.debug("rca enumerate failed: %s", e)
        return _abstain(f"topology enumeration failed: {e}", enabled=True, service=failing)

    if not isinstance(candidates, dict) or candidates.get("error") or not candidates.get("found"):
        return _abstain("no dependencies found for failing service", enabled=True, service=failing)

    blast, cycles = {}, {}
    try:
        blast = await tmcp.call_tool("get_blast_radius", {"service": failing, "maxDepth": depth})
    except Exception as e:
        logger.debug("rca blast radius skipped: %s", e)
    try:
        cycles = await tmcp.call_tool("get_dependency_cycles", {"maxDepth": depth})
    except Exception as e:
        logger.debug("rca cycles skipped: %s", e)

    enumerated = build_enumerated_set(candidates, blast, cycles)
    if not enumerated["paths"]:
        return _abstain("no enumerated dependency paths to select from", enabled=True, service=failing)

    selection = await _select_via_llm(failing, sig, enumerated, candidates, blast, cycles, max_paths)
    if selection is None:
        return _abstain("no LLM selection (model unavailable or declined)", enabled=True,
                        service=failing, candidates=candidates, blast=blast)

    lint_ok, violations = lint_selection(selection, enumerated)
    if not lint_ok:
        logger.info("rca citation lint failed for %s: %s", failing, violations)
        return _abstain("citation lint failed: " + "; ".join(violations[:5]),
                        enabled=True, service=failing, selection=selection,
                        candidates=candidates, blast=blast)

    # Verify the model's claims PLUS the full structural chain of the chosen path, so a
    # supported narrative is one whose every cited edge still exists in the live graph.
    chosen = enumerated["paths"][selection["pathIndex"]]
    claims = _merge_claims(selection.get("claims") or [], chosen.get("edgeIds") or [])
    try:
        verify = await tmcp.call_tool("verify_rca_claims", {"claims": claims})
    except Exception as e:
        logger.debug("rca verify failed: %s", e)
        return _abstain(f"symbolic verification failed: {e}", enabled=True, service=failing,
                        selection=selection, candidates=candidates, blast=blast)

    supported = int((verify or {}).get("supportedCount") or 0)
    total = int((verify or {}).get("totalClaims") or 0)
    all_supported = bool((verify or {}).get("allSupported"))
    faithfulness = (supported / total) if total else 0.0
    confidence = _as_float(selection.get("confidence"), 0.0)

    audit = {
        "faithfulnessScore": faithfulness,
        "supportedClaims": supported,
        "totalClaims": total,
        "factualityAlpha": settings.rca_factuality_alpha,
        "confidence": confidence,
    }

    # Conformal-style gate: abstain unless EVERY claim is supported, faithfulness clears the
    # risk budget, and the model is confident enough.
    min_faithfulness = 1.0 - settings.rca_factuality_alpha
    if total == 0 or not all_supported or faithfulness < min_faithfulness:
        return _abstain(
            f"faithfulness {faithfulness:.2f} < {min_faithfulness:.2f} or unsupported claims present",
            enabled=True, service=failing, selection=selection, verify=verify,
            audit={**audit, "abstained": True}, candidates=candidates, blast=blast)
    if confidence < settings.rca_min_confidence:
        return _abstain(
            f"model confidence {confidence:.2f} < {settings.rca_min_confidence:.2f}",
            enabled=True, service=failing, selection=selection, verify=verify,
            audit={**audit, "abstained": True}, candidates=candidates, blast=blast)

    citations = _build_citations(verify)
    return {
        "enabled": True,
        "abstained": False,
        "service": failing,
        "rootCauseService": selection.get("rootCauseService"),
        "narrative": (selection.get("narrative") or "").strip(),
        "path": {
            "pathIndex": chosen.get("pathIndex"),
            "services": chosen.get("services"),
            "edgeIds": chosen.get("edgeIds"),
            "causalConfirmed": chosen.get("causalConfirmed"),
        },
        "citations": citations,
        "blastRadius": (blast or {}).get("affected") or [],
        "cycles": enumerated["cycles"],
        "audit": {**audit, "abstained": False},
        "model": settings.active_llm_model,
    }


# ── LLM selection (provider-agnostic strict tool-use) ──


async def _select_via_llm(
    failing: str,
    sig: dict,
    enumerated: dict,
    candidates: dict,
    blast: dict,
    cycles: dict,
    max_paths: int,
) -> dict | None:
    prompt = _build_prompt(failing, sig, enumerated, candidates, blast, cycles, max_paths)
    provider = settings.llm_provider
    try:
        if provider == "gemini" and settings.gemini_api_key:
            return await _select_gemini(prompt)
        if settings.anthropic_api_key:
            return await _select_anthropic(prompt)
    except Exception as e:  # any provider error → abstain
        logger.debug("rca LLM selection error: %s", e)
    return None


_RCA_SYSTEM = (
    "You are Mendr's root-cause analyst. You are given a CLOSED, enumerated set of REAL dependency "
    "paths (with real node/edge ids) for a failing microservice. Select exactly one path and explain "
    "the likely root cause. You may ONLY cite ids present in the set. Inventing a service, edge, id, or "
    "path is a critical error — if the evidence is insufficient, still select the best-supported path "
    "and keep the narrative strictly to what the cited edges prove."
)


def _build_prompt(failing, sig, enumerated, candidates, blast, cycles, max_paths) -> str:
    paths = enumerated["paths"][:max_paths]
    lines = [
        f"Failing service (symptom): {failing}",
        f"ErrorSignature: {_compact(sig)}",
        "",
        "ENUMERATED CANDIDATE PATHS (select a pathIndex; cite only these edge/node ids):",
        _compact(paths),
        "",
        "DEPENDENCIES (causalConfirmed=true means an evidence-backed cascade exists downstream):",
        _compact((candidates or {}).get("dependencies")),
    ]
    affected = (blast or {}).get("affected") or []
    if affected:
        lines += ["", f"BLAST RADIUS (who fails if {failing} fails): {_compact(affected)}"]
    if (cycles or {}).get("cycles"):
        lines += ["", f"DEPENDENCY CYCLES (architectural finding): {_compact(cycles['cycles'])}"]
    lines += [
        "",
        "Call select_root_cause_path with a pathIndex from the set above, a rootCauseService that "
        "appears in that path, claims that cite only enumerated ids, and a grounded narrative.",
    ]
    return "\n".join(lines)


async def _select_anthropic(prompt: str) -> dict | None:
    import anthropic

    client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
    tool = {
        "name": _SELECT_TOOL_NAME,
        "description": _SELECT_TOOL_DESCRIPTION,
        "input_schema": _select_tool_input_schema(),
    }
    resp = await client.messages.create(
        model=settings.anthropic_model,
        max_tokens=settings.max_tokens,
        system=_RCA_SYSTEM,
        tools=[tool],
        tool_choice={"type": "tool", "name": _SELECT_TOOL_NAME},
        messages=[{"role": "user", "content": prompt}],
    )
    for block in resp.content:
        if getattr(block, "type", None) == "tool_use" and block.name == _SELECT_TOOL_NAME:
            return dict(block.input)
    return None


async def _select_gemini(prompt: str) -> dict | None:
    import google.generativeai as genai

    genai.configure(api_key=settings.gemini_api_key)
    declaration = {
        "name": _SELECT_TOOL_NAME,
        "description": _SELECT_TOOL_DESCRIPTION,
        "parameters": _select_tool_input_schema(),
    }
    model = genai.GenerativeModel(
        settings.gemini_model,
        system_instruction=_RCA_SYSTEM,
        tools=[{"function_declarations": [declaration]}],
    )
    resp = await model.generate_content_async(
        [{"role": "user", "parts": [{"text": prompt}]}],
        tool_config={"function_calling_config": {
            "mode": "ANY", "allowed_function_names": [_SELECT_TOOL_NAME]}},
        generation_config={"max_output_tokens": settings.max_tokens},
    )
    candidates = getattr(resp, "candidates", None) or []
    if not candidates:
        return None
    for part in candidates[0].content.parts:
        fn = getattr(part, "function_call", None)
        if fn and fn.name == _SELECT_TOOL_NAME:
            return dict(fn.args) if fn.args else {}
    return None


# ── helpers ──


def _merge_claims(model_claims: list, path_edge_ids: list) -> list[dict]:
    """Model claims + one edge claim per chosen-path edge, de-duplicated."""
    out: list[dict] = []
    seen: set[str] = set()

    def _push(claim: dict) -> None:
        key = json.dumps({k: claim.get(k) for k in sorted(claim)}, sort_keys=True, default=str)
        if key not in seen:
            seen.add(key)
            out.append(claim)

    for c in model_claims:
        if isinstance(c, dict):
            _push(c)
    for eid in path_edge_ids:
        if _is_int(eid):
            _push({"type": "edge", "edgeId": int(eid)})
    return out


def _build_citations(verify: dict) -> list[dict]:
    citations = []
    for r in (verify or {}).get("results", []) or []:
        if not isinstance(r, dict) or not r.get("supported"):
            continue
        evidence = r.get("evidence")
        ev = evidence[0] if isinstance(evidence, list) and evidence else evidence
        citations.append({
            "kind": r.get("kind"),
            "claim": r.get("claim"),
            "evidence": ev,
        })
    return citations


def _abstain(reason: str, *, enabled: bool = True, **extra) -> dict:
    out = {"enabled": enabled, "abstained": True, "reason": reason}
    audit = extra.pop("audit", None)
    out["audit"] = audit if audit is not None else {"abstained": True}
    out.update(extra)
    return out


def _add_int(target: set, value) -> None:
    if _is_int(value):
        target.add(int(value))


def _is_int(value) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return True
    return isinstance(value, str) and value.strip().lstrip("-").isdigit()


def _as_float(value, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _compact(obj: Any) -> str:
    try:
        return json.dumps(obj, default=str, separators=(",", ":"))[: settings.max_context_chars]
    except (TypeError, ValueError):
        return str(obj)[: settings.max_context_chars]
