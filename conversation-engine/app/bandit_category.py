"""Phase 4 True REx: coerce / abort bandit_category tags before pending credit."""
from __future__ import annotations

CATEGORIES = (
    "STRUCTURAL_MAPPING",
    "DATA_COERCION",
    "ADD_DEFAULT",
    "FIELD_REMOVE",
    "RESPONSE_MAP",
    "ROUTING",
    "CORS",
)

_SET = set(CATEGORIES)


def normalize(category: str | None) -> str | None:
    if not category or not str(category).strip():
        return None
    u = str(category).strip().upper()
    return u if u in _SET else None


def coerce_or_abort(raw: str | None, allowed_arms: list[str] | None) -> str | None:
    """Return normalized category or None to abort the branch.

    Locked failure modes:
    - missing tag → abort
    - invalid tag → coerce only if exactly one allowed arm; else abort
    - valid but not in Thompson-sampled set → abort
    """
    allowed = []
    for a in allowed_arms or []:
        n = normalize(a)
        if n and n not in allowed:
            allowed.append(n)

    if raw is None or not str(raw).strip():
        return None

    norm = normalize(raw)
    if norm is not None:
        if not allowed or norm in allowed:
            return norm
        return None

    if len(allowed) == 1:
        return allowed[0]
    return None


def enforce_on_program(
    program: dict | None,
    allowed_arms: list[str] | None,
    assigned_category: str | None = None,
) -> tuple[dict | None, str | None]:
    """Attach coerced bandit_category or abort (return None, None).

    When the LLM omits the tag, fill from the diversity arm's assigned category
    (still must pass coerce_or_abort against the sampled set).
    """
    if not isinstance(program, dict):
        return None, None
    raw = program.get("bandit_category") or program.get("banditCategory")
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        raw = assigned_category
    coerced = coerce_or_abort(raw, allowed_arms)
    if coerced is None:
        return None, None
    out = dict(program)
    out["bandit_category"] = coerced
    return out, coerced
