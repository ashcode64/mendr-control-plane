"""Shared critic outcome parsing (verify / simulate) — no LLM graph deps."""
from __future__ import annotations

from typing import Any


def critic_ok(d: Any) -> bool | None:
    """
    True/False/None for a critic payload.
    Empty SimulationReport (results=[] + all counts 0) is UNKNOWN (None), not success.
    """
    if not isinstance(d, dict):
        return None
    if "ok" in d:
        return bool(d.get("ok"))
    if "valid" in d:
        return bool(d.get("valid"))
    if "faulted" in d or "mismatched" in d or "results" in d:
        try:
            passed = int(d.get("passed") or 0)
            faulted = int(d.get("faulted") or 0)
            mismatched = int(d.get("mismatched") or 0)
        except (TypeError, ValueError):
            return None
        results = d.get("results")
        empty_results = isinstance(results, list) and len(results) == 0
        no_cases = (results is None or empty_results) and passed == 0 and faulted == 0 and mismatched == 0
        if no_cases and ("results" in d or "faulted" in d or "mismatched" in d):
            return None
        if "faulted" in d or "mismatched" in d:
            return faulted == 0 and mismatched == 0
        if isinstance(d.get("passed"), (int, float)):
            return int(d.get("passed")) > 0
    if "passed" in d:
        p = d.get("passed")
        if isinstance(p, bool):
            return p
        if isinstance(p, (int, float)):
            return int(p) > 0
    status = str(d.get("status") or "").lower()
    if status in ("ok", "pass", "passed", "ready"):
        return True
    if status in ("fail", "failed", "error"):
        return False
    return None
