"""Internal diagnosis entry: runs the LangGraph loop on an ErrorSignature.

Complexity-gated:
  - deterministicDiff → Synthesis only (propose→verify→simulate→metamorphic→minimize→present)
  - UNKNOWN / multi-hop → Diagnostic context enrichment then Synthesis
Critics remain MCP verify_program + simulate_transform (deterministic).
Minimization runs after critics and before present so approve == already minimal.
"""
from __future__ import annotations

import logging
from typing import Any

from .bandit_category import enforce_on_program
from .config import settings
from .graph import build_graph
from .llm import Proposer
from .mcp_client import McpClient
from .prompts import SCHEMA_VERSION
from .schema_check import validates_against_schema

logger = logging.getLogger("mendr.diagnose")

_proposer = Proposer()
_mcp = McpClient()
_graph = build_graph(_proposer, _mcp)


def _provisional_confidence(
    *,
    ready: bool,
    refuse: bool,
    verification: Any,
    simulation: Any,
    metamorphic: Any,
    verified_candidates: Any,
) -> float:
    """Evidence-based provisional score for CE → AIS. AIS replaces with Venn-Abers pVa.

    Never returns a hardcoded 0.9 for "ready". Combines verify/simulate/metamorphic
    and resample cluster agreement when available.
    """
    if refuse:
        return 0.40
    if not ready:
        return 0.35

    signals: list[float] = []

    from .critics_outcome import critic_ok as _ok

    v = _ok(verification)
    if v is not None:
        signals.append(0.95 if v else 0.25)
    s = _ok(simulation)
    if s is not None:
        signals.append(0.90 if s else 0.30)

    if isinstance(metamorphic, dict):
        rate = metamorphic.get("passRate")
        if isinstance(rate, (int, float)):
            signals.append(max(0.0, min(1.0, float(rate))))
        else:
            passed = metamorphic.get("passed")
            total = metamorphic.get("total")
            if isinstance(passed, (int, float)) and isinstance(total, (int, float)) and total > 0:
                signals.append(max(0.0, min(1.0, float(passed) / float(total))))

    # Resample agreement: fraction of verified candidates that share the modal program shape.
    if isinstance(verified_candidates, list) and len(verified_candidates) >= 2:
        shapes: dict[str, int] = {}
        for item in verified_candidates:
            prog = None
            if isinstance(item, dict):
                prog = item.get("program") if isinstance(item.get("program"), dict) else item
            if isinstance(prog, dict):
                key = str(sorted((k, str(prog.get(k))) for k in ("ops", "type", "renames") if k in prog))
                shapes[key] = shapes.get(key, 0) + 1
        if shapes:
            n = sum(shapes.values())
            win = max(shapes.values())
            signals.append(win / n if n else 0.5)

    if not signals:
        return 0.55
    return max(0.0, min(1.0, sum(signals) / len(signals)))


def _should_diagnose_first(complexity: dict | None, signature: dict) -> bool:
    complexity = complexity or {}
    # D5: skip diagnose-first only when deterministic coverage is *complete*.
    # Partial registry hits set deterministicPartial and must still diagnose residuals.
    if complexity.get("deterministicDiff") and not complexity.get("deterministicPartial", False):
        return False
    category = (complexity.get("category") or signature.get("category") or "").upper()
    if category in ("UNKNOWN", ""):
        return True
    if complexity.get("multiHop"):
        return True
    if not signature.get("change_type"):
        return True
    return False


def _has_rfc9457_problem(sig: dict, complexity: dict | None) -> bool:
    """True only when RFC 9457 Problem Details already carry structured root-cause slots.

    Used to skip CIL when Path A1 has already disambiguated the failure.
    Must NOT treat bare ErrorSignature json_path+change_type as sufficient — that
    reintroduces top-1 starvation when driftedFields has N>1.
    """
    complexity = complexity or {}
    if not (complexity.get("hasProblemJson") or sig.get("problemDetail")):
        return False
    pd = sig.get("problemDetail") if isinstance(sig.get("problemDetail"), dict) else {}
    ext = pd.get("extensions") if isinstance(pd.get("extensions"), dict) else {}
    if ext.get("json_path") or ext.get("template_id") or ext.get("errors"):
        return True
    if pd.get("type") and "problem" in str(pd.get("type", "")).lower():
        if sig.get("json_path") or sig.get("template_id"):
            return True
    return False


def _has_structured_problem(sig: dict, complexity: dict | None) -> bool:
    """Skip Drain3 mining when Problem Details or signature slots already disambiguated."""
    if _has_rfc9457_problem(sig, complexity):
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
    triggering_payload: dict | None = None,
    spec_trust: float | None = None,
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
            try:
                playbook = await tmcp.call_tool("get_ace_playbook", {
                    "category": sig.get("category") or (complexity or {}).get("category"),
                    "changeType": sig.get("change_type"),
                })
                diagnosis["acePlaybook"] = (playbook or {}).get("bullets") or []
                diagnosis["aceSuccessBullets"] = (playbook or {}).get("successBullets") or []
                diagnosis["aceFailureWarnOffs"] = (playbook or {}).get("failureWarnOffs") or []
            except Exception as pe:
                logger.debug("ace playbook fetch skipped: %s", pe)
            try:
                coords = sig.get("contract_coords") or {}
                rh = await tmcp.call_tool("get_repair_heuristics", {
                    "sourceService": (
                        sig.get("sourceService")
                        or coords.get("sourceService")
                        or coords.get("source")
                    ),
                    "targetService": coords.get("service") or coords.get("targetService"),
                    "endpoint": coords.get("endpoint"),
                    "category": sig.get("category") or (complexity or {}).get("category"),
                    "changeType": sig.get("change_type"),
                })
                diagnosis["repairHeuristics"] = (rh or {}).get("heuristics") or []
                diagnosis["repairSuccessHeuristics"] = (rh or {}).get("successHeuristics") or []
                diagnosis["repairFailureWarnOffs"] = (rh or {}).get("failureWarnOffs") or []
                diagnosis["topologyScope"] = (rh or {}).get("topologyScope")
            except Exception as he:
                logger.debug("repair heuristics fetch skipped: %s", he)
            try:
                mm = await tmcp.call_tool("get_meta_memory", {
                    "category": sig.get("category") or (complexity or {}).get("category"),
                    "changeType": sig.get("change_type"),
                })
                diagnosis["metaMemory"] = (mm or {}).get("rules") or []
            except Exception as me:
                logger.debug("meta memory fetch skipped: %s", me)
            try:
                cp = await tmcp.call_tool("get_compiled_prompt", {"promptKind": "propose_addendum"})
                if cp and cp.get("found") and cp.get("promptText"):
                    diagnosis["compiledPrompt"] = cp.get("promptText")
                    diagnosis["compiledPromptMeta"] = {
                        "version": cp.get("version"),
                        "compiler": cp.get("compiler"),
                        "datasetSize": cp.get("datasetSize"),
                    }
            except Exception as ce:
                logger.debug("compiled prompt fetch skipped: %s", ce)
            # Phase 7: do NOT inject raw cross-tenant pool into the LLM prompt.
            # Imports materialize into local skill_library / repair_heuristics / ace_playbook
            # only after critic + harness; diagnose already uses match_skill / heuristics / playbook.
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

    # Phase 0b / 8.3a: Causal Intervention Localization
    # Candidates from Java driftedFields only (never LLM-flagged).
    # N=1 → PS probe; N>1 → ddmin Path A/B/C.
    ddmin_meta: dict | None = None
    refuse_ddmin = False
    verified_candidates: list[dict] = []
    drifted = (complexity or {}).get("driftedFields") or sig.get("driftedFields") or []
    if not isinstance(drifted, list):
        drifted = []

    if len(drifted) == 1 and not _has_rfc9457_problem(sig, complexity):
        verified_candidates = await _ps_probe_single(
            tmcp, drifted[0], cases, sig, complexity
        )
    elif len(drifted) > 1 and not _has_rfc9457_problem(sig, complexity):
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
                    verified_candidates.append({
                        "field": jp,
                        "candidate_op": f,
                        "causally_verified": True,
                        "source": "ddmin_minimal",
                    })
                # Annotate non-minimal drifted fields as ruled out (not sufficient alone)
                minimal_paths = {
                    (f.get("json_path") if isinstance(f, dict) else None)
                    for f in (ddmin_meta.get("minimal") or [])
                }
                for f in drifted:
                    if not isinstance(f, dict):
                        continue
                    jp = f.get("json_path") or f.get("path")
                    if jp and jp not in minimal_paths:
                        verified_candidates.append({
                            "field": jp,
                            "candidate_op": f.get("minimal_op") or f,
                            "causally_verified": False,
                            "source": "ddmin_ruled_out",
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
    skill_meta: dict | None = None

    # Phase 3a LILO: sketch-matched skill macro (RegressionHarness-gated)
    if deterministic_program is None:
        try:
            holes = sketch.get("holes") or []
            hole0 = holes[0] if holes and isinstance(holes[0], dict) else {}
            allowed = hole0.get("allowedOpcodes") or sketch.get("allowedOpcodes") or []
            skill_meta = await tmcp.call_tool("match_skill", {
                "changeType": sig.get("change_type") or hole0.get("change_type"),
                "category": sig.get("category") or (complexity or {}).get("category"),
                "allowedOpcodes": allowed,
                "jsonPath": hole0.get("json_path") or sketch.get("json_path") or sig.get("json_path"),
            })
            if skill_meta and skill_meta.get("matched") and isinstance(skill_meta.get("program"), dict):
                deterministic_program = skill_meta["program"]
                logger.info(
                    "LILO skill fast-path matched key=%s support=%s",
                    skill_meta.get("skillKey"),
                    skill_meta.get("supportCount"),
                )
        except Exception as se:
            logger.debug("skill match skipped: %s", se)

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

    user_message = _propose_prompt(sig, sketch, diagnosis, bandit_meta, verified_candidates)
    # Twin gates: prefer explicit request fields; fall back to signature payload/trust.
    explicit_trigger = triggering_payload if isinstance(triggering_payload, dict) else None
    if explicit_trigger is None and isinstance(sig, dict):
        for key in ("payload", "requestPayload", "failingPayload"):
            if isinstance(sig.get(key), dict):
                explicit_trigger = sig[key]
                break
    trust = spec_trust
    if trust is None and isinstance(sig, dict):
        for key in ("specTrust", "spec_trust"):
            if isinstance(sig.get(key), (int, float)):
                trust = float(sig[key])
                break
    context = {
        "errorSignature": sig,
        "sketch": sketch,
        "diagnosis": diagnosis,
        "templateMeta": template_meta,
        "bandit": bandit_meta,
        "skill": skill_meta,
        "verifiedCandidates": verified_candidates,
        "service": (sig.get("contract_coords") or {}).get("service"),
        "endpoint": (sig.get("contract_coords") or {}).get("endpoint"),
        "direction": (sig.get("contract_coords") or {}).get("direction", "REQUEST"),
    }
    if explicit_trigger is not None:
        context["triggeringPayload"] = explicit_trigger
    if trust is not None:
        context["specTrust"] = trust

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
            "verifiedCandidates": verified_candidates,
            "model": settings.active_llm_model,
            "errorSignature": sig,
            "sketch": sketch,
            "diagnosis": diagnosis,
            "templateMeta": template_meta,
            "confidence": _provisional_confidence(
                ready=False,
                refuse=True,
                verification=None,
                simulation=None,
                metamorphic=None,
                verified_candidates=verified_candidates,
            ),
            "deployable": False,
            "refuseAutoHeal": True,
            "owner_action_required": True,
            "lagReason": (ddmin_meta or {}).get("abortReason"),
            "lagEvidence": list((diagnosis or {}).get("lagEvidence") or []) if diagnosis else [],
        }

    # Deterministic Synthesis-only: skip LLM propose
    if deterministic_program is not None:
        rationale = (
            f"LILO skill fast-path ({skill_meta.get('skillKey')})"
            if skill_meta and skill_meta.get("matched")
            else "deterministic hole-fill (no LLM)"
        )
        init = {
            "user_message": user_message,
            "context": context,
            "cases": cases,
            "prior_turns": prior_turns or [],
            "tenant_id": tenant_id,
            "candidate": deterministic_program,
            "rationale": rationale,
            "iterations": 1,
            "bandit": bandit_meta,
            "ddmin": ddmin_meta,
        }
        last = await _run_critics_only(
            deterministic_program, context, cases, tenant_id, bandit_meta, ddmin_meta, rationale
        )
    elif bandit_meta and bandit_meta.get("engaged") and bandit_meta.get("arms"):
        # Phase 4 True REx: semantic diversity batch → local Beta → Thompson pick
        last = await _true_rex_diversity(
            tmcp=tmcp,
            user_message=user_message,
            context=context,
            cases=cases,
            prior_turns=prior_turns or [],
            tenant_id=tenant_id,
            bandit_meta=bandit_meta,
            ddmin_meta=ddmin_meta,
        )
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

    # Merge program-shaped resamples for s₆/s₁ (REx arms + winner). Keep ddmin probes too.
    verified_candidates = _merge_program_candidates(
        verified_candidates,
        last.get("programCandidates"),
        last.get("candidate"),
    )

    provisional = _provisional_confidence(
        ready=ready,
        refuse=refuse,
        verification=last.get("verification"),
        simulation=last.get("simulation"),
        metamorphic=last.get("metamorphic"),
        verified_candidates=verified_candidates,
    )
    # s₁: prefer provider token logprobs on the winning program when present.
    token_logprobs = None
    cand = last.get("candidate")
    if isinstance(cand, dict):
        token_logprobs = cand.get("tokenLogprobs") or cand.get("_tokenLogprobs")
        if token_logprobs is None and cand.get("_s1LogprobConfidence") is not None:
            token_logprobs = cand.get("_s1LogprobConfidence")
    from .generation_confidence import resolve_s1
    s1_score, s1_source = resolve_s1(logprobs=token_logprobs, verbalized=provisional)
    confidence_out = provisional if s1_score is None else s1_score

    # Additive, gated: a verified+cited topology RCA narrative (never gates the heal).
    rca_narrative: dict | None = None
    if settings.rca_narrative_enabled:
        try:
            from .rca_narrative import run_rca_narrative
            rca_narrative = await run_rca_narrative(sig, tmcp, tenant_id=tenant_id)
        except Exception as e:
            logger.debug("rca narrative skipped: %s", e)

    out = {
        "status": status,
        "program": last.get("candidate"),
        "rationale": last.get("rationale"),
        "assistantText": last.get("assistant_text"),
        "verification": last.get("verification"),
        "simulation": last.get("simulation"),
        "metamorphic": last.get("metamorphic"),
        "minimization": last.get("minimization"),
        "ddmin": ddmin_meta,
        "bandit": last.get("bandit") or bandit_meta,
        "skill": skill_meta,
        "verifiedCandidates": verified_candidates,
        "model": settings.active_llm_model,
        "errorSignature": sig,
        "sketch": sketch,
        "diagnosis": diagnosis,
        "templateMeta": template_meta,
        "confidence": confidence_out,
        "generationConfidenceSource": s1_source,
        "deployable": ready and not refuse,
        "refuseAutoHeal": refuse,
        "owner_action_required": bool(diagnosis and diagnosis.get("owner_action_required")),
        "lagReason": (diagnosis or {}).get("lagReason") if diagnosis else None,
        "lagEvidence": list((diagnosis or {}).get("lagEvidence") or []) if diagnosis else [],
        "rcaNarrative": rca_narrative,
    }
    if token_logprobs is not None:
        out["tokenLogprobs"] = token_logprobs
    return out


def _merge_program_candidates(
    existing: list | None,
    program_candidates: Any,
    winner: Any,
) -> list:
    """Ensure verifiedCandidates include {program: ...} entries for semantic clustering."""
    out: list = list(existing) if isinstance(existing, list) else []
    seen: set[str] = set()

    def _key(prog: dict) -> str:
        try:
            ops = prog.get("ops")
            return str(("ops", ops if ops is not None else prog.get("type")))
        except Exception:
            return str(id(prog))

    def _add(prog: Any, meta: dict | None = None) -> None:
        if not isinstance(prog, dict):
            return
        if not (prog.get("ops") is not None or prog.get("type") or prog.get("renames")):
            return
        k = _key(prog)
        if k in seen:
            return
        seen.add(k)
        entry = {"program": prog, "causally_verified": True}
        if meta:
            entry.update(meta)
        out.append(entry)

    if isinstance(program_candidates, list):
        for item in program_candidates:
            if isinstance(item, dict) and isinstance(item.get("program"), dict):
                _add(item["program"], {k: v for k, v in item.items() if k != "program"})
            else:
                _add(item)
    _add(winner, {"winner": True})
    return out


async def _true_rex_diversity(
    tmcp: McpClient,
    user_message: str,
    context: dict,
    cases: list,
    prior_turns: list,
    tenant_id: str | None,
    bandit_meta: dict,
    ddmin_meta: dict | None,
) -> dict:
    """Semantic diversity batch: ≤3 category-tagged programs → local Beta → Thompson pick."""
    arms = [a for a in (bandit_meta.get("arms") or []) if isinstance(a, dict)]
    allowed = list(bandit_meta.get("allowedCategories") or [
        a.get("category") for a in arms if a.get("category")
    ])
    session_id = bandit_meta.get("sessionId")
    registered = 0
    program_candidates: list[dict] = []

    for arm in arms[:3]:
        cat = arm.get("category")
        if not cat:
            continue
        hint = (
            f"{user_message}\n\n"
            f"True REx diversity arm: set bandit_category to exactly {cat}. "
            f"Do not invent other categories. Prefer strategies in {cat}."
        )
        try:
            program, _text = await _proposer.propose(hint, context, [], prior_turns)
        except Exception as e:
            logger.debug("diversity propose failed for %s: %s", cat, e)
            continue
        program, coerced = enforce_on_program(program, allowed, assigned_category=cat)
        if program is None:
            logger.debug("diversity arm aborted (category) for %s", cat)
            continue
        if not session_id:
            continue
        # Critics first, then minimize, then register — matches approve=minimal invariant.
        verification = await tmcp.verify_program(program)
        success = bool((verification or {}).get("valid"))
        if success and cases:
            try:
                sim = await tmcp.simulate_transform(program, cases)
                if isinstance(sim, dict):
                    if sim.get("ok") is False:
                        success = False
                    elif "faulted" in sim or "mismatched" in sim:
                        success = int(sim.get("faulted") or 0) == 0 and int(sim.get("mismatched") or 0) == 0
            except Exception:
                pass
        if not success:
            logger.debug("diversity arm failed critics for %s", cat)
            continue
        try:
            from .minimize_helpers import (
                apply_citation_scrub,
                declared_field_types,
                explicit_triggering_payload,
                merge_minimized_candidate,
                spec_trust,
                unresolvable_paths,
            )
            sketch = (context or {}).get("sketch") or {}
            sig = (context or {}).get("errorSignature") or (context or {}).get("error_signature") or {}
            if not isinstance(sig, dict):
                sig = {}
            ctx = context or {}
            min_report = await tmcp.minimize_program(
                program=program,
                cases=cases or [],
                triggering_payload=explicit_triggering_payload(ctx, sig),
                spec_trust=spec_trust(ctx, sig),
                allowed_opcodes=sketch.get("allowedOpcodes"),
                declared_field_types=declared_field_types(ctx, sketch, sig),
                unresolvable_paths=unresolvable_paths(ctx, sketch, sig),
            )
            if isinstance(min_report, dict) and min_report.get("minimized"):
                merged = merge_minimized_candidate(program, min_report) or program
                scrub = apply_citation_scrub(program, merged, (program or {}).get("rationale"), None)
                program = merged
                if isinstance(program, dict) and scrub.get("rationale"):
                    program = dict(program)
                    program["rationale"] = scrub["rationale"]
        except Exception as e:
            logger.debug("diversity minimize failed — skipping arm: %s", e)
            continue
        entry: dict[str, Any] = {
            "program": program,
            "banditCategory": coerced,
            "causally_verified": True,
        }
        try:
            reg = await tmcp.call_tool("register_local_program", {
                "sessionId": session_id,
                "banditCategory": coerced,
                "program": program,
            })
        except Exception as e:
            logger.debug("register_local_program failed: %s", e)
            # Still keep for s₆/s₁ even when bandit register fails.
            if isinstance(program, dict):
                program_candidates.append(entry)
            continue
        if not (reg or {}).get("registered"):
            if isinstance(program, dict):
                program_candidates.append(entry)
            continue
        local_arm_id = ((reg or {}).get("arm") or {}).get("localArmId")
        registered += 1
        if isinstance(program, dict):
            entry["localArmId"] = local_arm_id
            program_candidates.append(entry)
        # Observe local posterior on the minimized, critic-passed arm
        try:
            await tmcp.call_tool("observe_local_bandit", {
                "sessionId": session_id,
                "localArmId": local_arm_id,
                "success": success,
            })
        except Exception as e:
            logger.debug("observe_local_bandit failed: %s", e)
    if registered == 0:
        # Fall back to single-graph propose with category constraint in prompt
        init = {
            "user_message": user_message,
            "context": context,
            "cases": cases,
            "prior_turns": prior_turns,
            "tenant_id": tenant_id,
            "bandit": bandit_meta,
            "ddmin": ddmin_meta,
        }
        last: dict = {}
        async for chunk in _graph.astream(init, stream_mode="values"):
            last = chunk
        # Enforce category on winner
        cand = last.get("candidate")
        allowed_cats = allowed
        assigned = bandit_meta.get("category")
        enforced, coerced = enforce_on_program(cand, allowed_cats, assigned_category=assigned)
        if enforced is None and cand is not None:
            last["status"] = "unverifiable"
            last["rationale"] = "bandit_category aborted (invalid/missing tag)"
            last["candidate"] = None
            last["programCandidates"] = program_candidates
            return last
        if enforced is not None:
            last["candidate"] = enforced
            bm = dict(bandit_meta)
            bm["category"] = coerced
            # No invented localArmId — graph fallback is not a registered True REx arm.
            # Keep engaged=false so Approve does not enqueue pending credit without a real arm.
            bm["engaged"] = False
            bm.pop("localArmId", None)
            last["bandit"] = bm
            if isinstance(enforced, dict):
                program_candidates.append({
                    "program": enforced,
                    "banditCategory": coerced,
                    "causally_verified": True,
                    "graphFallback": True,
                })
        last["programCandidates"] = program_candidates
        return last

    pick = await tmcp.call_tool("pick_local_bandit", {"sessionId": session_id})
    if not (pick or {}).get("picked"):
        return {
            "candidate": None,
            "status": "unverifiable",
            "rationale": "True REx: no local program arms survived",
            "bandit": bandit_meta,
            "ddmin": ddmin_meta,
            "programCandidates": program_candidates,
        }

    program = (pick or {}).get("program")
    category = (pick or {}).get("category")
    local_arm_id = (pick or {}).get("localArmId")
    bm = dict(bandit_meta)
    bm["category"] = category
    bm["localArmId"] = local_arm_id
    bm["engaged"] = True
    bm["picked"] = pick.get("arm")
    rationale = f"True REx local Thompson pick ({category})"
    last = await _run_critics_only(
        program, context, cases, tenant_id, bm, ddmin_meta, rationale
    )
    last["bandit"] = bm
    last["programCandidates"] = program_candidates
    if isinstance(last.get("candidate"), dict) and category:
        cand = dict(last["candidate"])
        cand["bandit_category"] = category
        last["candidate"] = cand
    return last


async def _run_critics_only(
    program: dict,
    context: dict,
    cases: list,
    tenant_id: str | None,
    bandit_meta: dict | None,
    ddmin_meta: dict | None,
    rationale: str | None = None,
) -> dict:
    """Verify → simulate → metamorphic → minimize for a deterministically materialized program."""
    from .minimize_helpers import (
        apply_citation_scrub,
        declared_field_types,
        explicit_triggering_payload,
        merge_minimized_candidate,
        spec_trust,
        unresolvable_paths,
    )

    why = rationale or "deterministic hole-fill (no LLM)"
    tmcp = _mcp.for_tenant(tenant_id)
    verification = await tmcp.verify_program(program)
    if not verification.get("valid"):
        return {
            "candidate": program,
            "verification": verification,
            "status": "unverifiable",
            "rationale": f"{why} — failed verify",
            "bandit": bandit_meta,
            "ddmin": ddmin_meta,
        }
    simulation = await tmcp.simulate_transform(program, cases or [])
    inputs = []
    for c in cases or []:
        if isinstance(c, dict) and c.get("input") is not None:
            inputs.append(c["input"])
    metamorphic = await tmcp.verify_properties(program, inputs)

    minimization = None
    candidate = program
    scrubbed_rationale = why
    try:
        ctx = context or {}
        sketch = ctx.get("sketch") or {}
        sig = ctx.get("errorSignature") or ctx.get("error_signature") or {}
        if not isinstance(sig, dict):
            sig = {}
        minimization = await tmcp.minimize_program(
            program=program,
            cases=cases or [],
            triggering_payload=explicit_triggering_payload(ctx, sig),
            spec_trust=spec_trust(ctx, sig),
            allowed_opcodes=sketch.get("allowedOpcodes"),
            declared_field_types=declared_field_types(ctx, sketch, sig),
            unresolvable_paths=unresolvable_paths(ctx, sketch, sig),
        )
        candidate = merge_minimized_candidate(program, minimization) or program
        if isinstance(minimization, dict) and minimization.get("minimized") and candidate is not program:
            scrub = apply_citation_scrub(program, candidate, why, None)
            scrubbed_rationale = scrub.get("rationale") or why
            if isinstance(candidate, dict) and scrub.get("rationale"):
                candidate = dict(candidate)
                candidate["rationale"] = scrub["rationale"]
            if isinstance(minimization, dict):
                minimization = dict(minimization)
                minimization["droppedPaths"] = scrub.get("droppedPaths") or []
    except Exception as e:
        logger.debug("minimize skipped: %s", e)
        minimization = {
            "error": str(e),
            "minimized": False,
            "fellBack": True,
            "engine": "minimize_unreachable",
        }

    return {
        "candidate": candidate,
        "verification": verification,
        "simulation": simulation,
        "metamorphic": metamorphic,
        "minimization": minimization,
        "status": "ready",
        "rationale": scrubbed_rationale,
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
    return {"schemaVersion": SCHEMA_VERSION, "ops": [op]}


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


def _propose_prompt(
    sig: dict,
    sketch: dict,
    diagnosis: dict | None,
    bandit: dict | None = None,
    verified_candidates: list | None = None,
) -> str:
    lines = [
        "Propose a minimal MendrScript program that fixes this ErrorSignature.",
        "Do NOT invent fields outside the signature. Prefer filling the sketch hole.",
        f"ErrorSignature: {sig}",
        f"Sketch: {sketch}",
    ]
    if verified_candidates:
        verified = [c for c in verified_candidates if isinstance(c, dict) and c.get("causally_verified")]
        ruled_out = [c for c in verified_candidates if isinstance(c, dict) and not c.get("causally_verified")]
        if verified:
            lines.append(f"causally_verified_root_causes: {verified}")
        if ruled_out:
            lines.append(f"tested_and_ruled_out: {ruled_out}")
    if diagnosis and diagnosis.get("aceSuccessBullets"):
        lines.append("ACE_Playbook_SUCCESS: " + "; ".join(
            str(b.get("bullet")) for b in diagnosis["aceSuccessBullets"][:8] if isinstance(b, dict)
        ))
    if diagnosis and diagnosis.get("aceFailureWarnOffs"):
        lines.append("ACE_Playbook_FAILURE_warn_offs: " + "; ".join(
            str(b.get("bullet")) for b in diagnosis["aceFailureWarnOffs"][:8] if isinstance(b, dict)
        ))
    if diagnosis and diagnosis.get("repairSuccessHeuristics"):
        lines.append("Topology_Heuristics_SUCCESS: " + "; ".join(
            str(h.get("heuristic")) for h in diagnosis["repairSuccessHeuristics"][:8] if isinstance(h, dict)
        ))
    if diagnosis and diagnosis.get("repairFailureWarnOffs"):
        lines.append("Topology_Heuristics_FAILURE_warn_offs: " + "; ".join(
            str(h.get("heuristic")) for h in diagnosis["repairFailureWarnOffs"][:8] if isinstance(h, dict)
        ))
    if diagnosis and diagnosis.get("metaMemory"):
        lines.append("MetaMemory_rules: " + "; ".join(
            str(r.get("rule")) for r in diagnosis["metaMemory"][:8] if isinstance(r, dict)
        ))
    if diagnosis and diagnosis.get("compiledPrompt"):
        lines.append("CompiledPrompt_addendum:\n" + str(diagnosis["compiledPrompt"])[:2000])
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
        lines.append(
            "REQUIRED: set propose_program.bandit_category to exactly one of "
            f"{cats}. Invalid tags abort the branch."
        )
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


async def _ps_probe_single(
    tmcp: McpClient,
    field: dict,
    cases: list,
    sig: dict,
    complexity: dict | None,
) -> list[dict]:
    """N=1 Probability-of-Sufficiency probe via simulate_transform + fail-closed schema check."""
    if not isinstance(field, dict):
        return []
    path = field.get("json_path") or field.get("path")
    minimal_op = field.get("minimal_op")
    if not isinstance(minimal_op, dict):
        ct = str(field.get("change_type") or "").upper()
        if path and ("COERCE" in ct or "TYPE" in ct):
            minimal_op = {
                "op": "coerce",
                "path": path,
                "targetType": field.get("expected_type") or "string",
            }
        elif path and ("DEFAULT" in ct or "ADD" in ct):
            minimal_op = {"op": "default", "path": path, "value": "", "on": "absent"}
        elif path and "REMOVE" in ct:
            minimal_op = {"op": "remove", "path": path}
        elif field.get("from") and field.get("to"):
            minimal_op = {"op": "rename", "from": field["from"], "to": field["to"]}
        else:
            return [{
                "field": path,
                "candidate_op": field,
                "causally_verified": False,
                "source": "ps_probe_no_op",
            }]

    program = {
        "schemaVersion": SCHEMA_VERSION,
        "rationale": f"causal PS probe: correcting {path} alone",
        "ops": [minimal_op],
    }
    failing_case = None
    if cases:
        first = cases[0]
        if isinstance(first, dict):
            failing_case = first if "input" in first else {"input": first.get("payload") or first}

    sim: dict = {}
    try:
        sim = await tmcp.simulate_transform(program, [failing_case] if failing_case else [])
    except Exception as e:
        logger.debug("PS probe simulate failed: %s", e)
        return [{
            "field": path,
            "candidate_op": minimal_op,
            "causally_verified": False,
            "source": "ps_probe_sim_error",
        }]

    target_schema = await _load_target_schema(tmcp, sig)
    resolved = validates_against_schema(sim, target_schema)
    return [{
        "field": path,
        "candidate_op": minimal_op,
        "causally_verified": resolved,
        "source": "ps_probe",
        "simulation": {"ok": resolved},
    }]


async def _load_target_schema(tmcp: McpClient, sig: dict) -> dict | None:
    """Best-effort contract schema for fail-closed PS validation."""
    coords = sig.get("contract_coords") if isinstance(sig.get("contract_coords"), dict) else {}
    service = coords.get("service") or coords.get("targetService")
    endpoint = coords.get("endpoint")
    direction = coords.get("direction") or "REQUEST"
    if not service or not endpoint:
        return None
    try:
        contract = await tmcp.get_contract(service, endpoint, direction)
        if not isinstance(contract, dict):
            return None
        for key in ("inferredSchema", "inferred_schema", "schema", "receiverSchema", "exampleSchema"):
            schema = contract.get(key)
            if isinstance(schema, dict) and schema:
                return schema
        nested = contract.get("contract")
        if isinstance(nested, dict):
            for key in ("inferredSchema", "inferred_schema", "schema"):
                schema = nested.get(key)
                if isinstance(schema, dict) and schema:
                    return schema
    except Exception as e:
        logger.debug("get_contract for PS probe skipped: %s", e)
    return None
