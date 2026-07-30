"""Shared helpers for remediation minimization (twin gates, merge, scrub)."""
from __future__ import annotations


def explicit_triggering_payload(ctx: dict, sig: dict | None = None) -> dict | None:
    """Twin gate 2: only an *explicit* incident payload — never a bare simulation case."""
    for key in ("triggeringPayload", "triggering_payload", "payload"):
        if isinstance(ctx.get(key), dict):
            return ctx[key]
    if isinstance(sig, dict):
        for key in ("payload", "requestPayload", "failingPayload"):
            if isinstance(sig.get(key), dict):
                return sig[key]
    return None


def spec_trust(ctx: dict, sig: dict | None = None) -> float | None:
    for key in ("specTrust", "spec_trust"):
        if isinstance(ctx.get(key), (int, float)):
            return float(ctx[key])
    if isinstance(sig, dict):
        for key in ("specTrust", "spec_trust"):
            if isinstance(sig.get(key), (int, float)):
                return float(sig[key])
    return None


def declared_field_types(ctx: dict, sketch: dict, sig: dict) -> dict[str, str] | None:
    """Collect declared/expected types for twin-gated coerce removal."""
    out: dict[str, str] = {}
    for hole in sketch.get("holes") or []:
        if not isinstance(hole, dict):
            continue
        path = hole.get("json_path") or hole.get("path")
        typ = hole.get("expected_type") or hole.get("expectedType")
        if isinstance(path, str) and isinstance(typ, str) and path and typ:
            out[path] = typ
    for f in sig.get("driftedFields") or sig.get("drifted_fields") or []:
        if not isinstance(f, dict):
            continue
        path = f.get("json_path") or f.get("jsonPath") or f.get("path")
        typ = f.get("expected_type") or f.get("expectedType")
        if isinstance(path, str) and isinstance(typ, str) and path and typ:
            out[path] = typ
    raw = ctx.get("declaredFieldTypes") or ctx.get("declared_field_types")
    if isinstance(raw, dict):
        for k, v in raw.items():
            if isinstance(k, str) and isinstance(v, str):
                out[k] = v
    return out or None


def unresolvable_paths(ctx: dict, sketch: dict, sig: dict) -> list[str] | None:
    """Collect oneOf/anyOf / polymorphic pointers for L2 necessity UNRESOLVED."""
    out: list[str] = []
    seen: set[str] = set()

    def add(p: str | None) -> None:
        if isinstance(p, str) and p and p not in seen:
            seen.add(p)
            out.append(p)

    for key in ("unresolvablePaths", "unresolvable_paths"):
        raw = ctx.get(key)
        if isinstance(raw, list):
            for p in raw:
                if isinstance(p, str):
                    add(p)
    for hole in sketch.get("holes") or []:
        if not isinstance(hole, dict):
            continue
        path = hole.get("json_path") or hole.get("path")
        if isinstance(path, str) and ("oneOf" in path or "anyOf" in path or "oneof" in path.lower() or "anyof" in path.lower()):
            add(path)
        if hole.get("unresolvable") or hole.get("polymorphic"):
            add(path if isinstance(path, str) else None)
    for f in sig.get("driftedFields") or sig.get("drifted_fields") or []:
        if not isinstance(f, dict):
            continue
        path = f.get("json_path") or f.get("jsonPath") or f.get("path")
        if isinstance(path, str) and ("oneOf" in path or "anyOf" in path):
            add(path)
        if f.get("unresolvable") or f.get("oneOf") or f.get("anyOf"):
            add(path if isinstance(path, str) else None)
    return out or None


def merge_minimized_candidate(original: dict | None, minimize_result: dict | None) -> dict | None:
    """Replace ops with minimized AST while preserving bandit_category."""
    if not isinstance(original, dict):
        return original
    if not isinstance(minimize_result, dict):
        return original
    prog = minimize_result.get("program")
    if not isinstance(prog, dict) or "ops" not in prog or not isinstance(prog.get("ops"), list):
        return original
    if not minimize_result.get("minimized"):
        return original
    # Empty AST is undeployable via approve — keep original non-empty program.
    if not prog["ops"] and (original.get("ops") or []):
        return original
    merged = dict(original)
    merged["ops"] = prog["ops"]
    if prog.get("schemaVersion"):
        merged["schemaVersion"] = prog["schemaVersion"]
    return merged


def apply_citation_scrub(
    original: dict | None,
    minimized: dict | None,
    rationale: str | None,
    assistant_text: str | None,
) -> dict:
    from .citation_scrub import scrub_after_minimize
    return scrub_after_minimize(original, minimized, rationale, assistant_text)
