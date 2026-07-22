"""Phase 6 GEPA compile: prefer dspy.GEPA when a real compile succeeds; else MIPROv2-style.

Never accepts raw api_failures — caller must pass scrubbed offline examples only.
Never labels MIPRO output as compiler='gepa'.
"""
from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("mendr.gepa")


def compile_prompt(examples: list[dict[str, Any]]) -> dict[str, Any]:
    """Return {compiler, promptText, metrics}."""
    if not examples:
        return {
            "compiler": "mipro_fallback",
            "promptText": "",
            "metrics": {"examples": 0},
            "error": "empty_dataset",
        }

    gepa = _try_dspy_gepa(examples)
    if gepa is not None:
        return gepa
    return mipro_fallback(examples)


def mipro_fallback(examples: list[dict[str, Any]]) -> dict[str, Any]:
    tips: list[str] = []
    seen: set[str] = set()
    with_critic = 0

    def add(tip: str) -> None:
        t = (tip or "").strip()
        if not t or t in seen:
            return
        seen.add(t)
        tips.append(t)

    for ex in examples:
        if not isinstance(ex, dict):
            continue
        critic = ex.get("critic_text")
        if isinstance(critic, str) and len(critic.strip()) > 8:
            with_critic += 1
            add(_tip_from_critic(critic, ex.get("change_type"), ex.get("json_path")))
        ct = ex.get("change_type")
        if ct:
            add(f"Honor verified change_type={ct} — do not invent unrelated opcodes.")

    add("Use only scrubbed offline evidence; never invent fields outside ErrorSignature.")
    add("Prefer causally_verified_root_causes over untested guesses.")

    lines = ["MIPRO-style compiled propose addendum (fallback; not GEPA)."]
    for tip in tips[:16]:
        lines.append(f"- {tip}")
    return {
        "compiler": "mipro_fallback",
        "promptText": "\n".join(lines),
        "metrics": {
            "examples": len(examples),
            "withCritic": with_critic,
            "tips": min(len(tips), 16),
        },
    }


def _tip_from_critic(critic: str, change_type: Any, json_path: Any) -> str:
    c = critic.strip()
    if len(c) > 220:
        c = c[:220] + "…"
    lower = c.lower()
    path = f" near {json_path}" if json_path else ""
    if "protected" in lower or "authorization" in lower:
        return f"Never touch protected fields — critic: {c}"
    if "rename" in lower and "coerce" in lower:
        return f"Do not confuse FIELD_RENAME with TYPE_COERCE{path} — critic: {c}"
    ct = f" for {change_type}" if change_type else ""
    jp = f" at {json_path}" if json_path else ""
    return f"Critic feedback{ct}{jp}: {c}"


def _try_dspy_gepa(examples: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Return a GEPA result only if dspy.GEPA actually compiles; else None.

    Honest labeling lock: never return compiler='gepa' for heuristic/MIPRO output.
    """
    try:
        import dspy  # type: ignore
    except Exception:
        return None
    if not hasattr(dspy, "GEPA"):
        return None
    # Full multi-generation GEPA needs an LM and a metric. Until that operator path
    # is wired end-to-end, refuse the 'gepa' label and let mipro_fallback run.
    logger.info(
        "dspy.GEPA is installed but full reflective compile is not wired — "
        "using mipro_fallback (honest compiler tag)"
    )
    return None
