"""Scrub rationale / assistant text after remediation minimization drops ops/paths."""
from __future__ import annotations

import re
from typing import Any


def _op_paths(op: dict) -> set[str]:
    out: set[str] = set()
    for k in ("path", "from", "to", "key"):
        v = op.get(k)
        if isinstance(v, str) and v:
            out.add(v)
    return out


def paths_in_program(program: dict | None) -> set[str]:
    if not isinstance(program, dict):
        return set()
    paths: set[str] = set()
    for op in program.get("ops") or []:
        if isinstance(op, dict):
            paths |= _op_paths(op)
    return paths


def opcodes_in_program(program: dict | None) -> set[str]:
    if not isinstance(program, dict):
        return set()
    out: set[str] = set()
    for op in program.get("ops") or []:
        if isinstance(op, dict):
            o = op.get("op") or op.get("opcode")
            if isinstance(o, str) and o:
                out.add(o.lower())
    return out


def scrub_text_for_dropped_refs(
    text: str | None,
    dropped_paths: set[str],
    dropped_opcodes: set[str] | None = None,
) -> str:
    """Remove sentences that cite pruned JSON paths (or lone dropped opcode names)."""
    if not text:
        return ""
    if not dropped_paths and not dropped_opcodes:
        return text

    # Split on sentence boundaries while keeping delimiters loosely.
    parts = re.split(r"(?<=[.!?])\s+", text.strip())
    kept: list[str] = []
    for part in parts:
        lower = part.lower()
        drop = False
        for p in dropped_paths:
            # Match path as a token / quoted / slash form
            if p and (p in part or p.lower() in lower):
                drop = True
                break
        if not drop and dropped_opcodes:
            for op in dropped_opcodes:
                # Only scrub if the opcode appears as a word and no remaining path context
                if re.search(rf"\b{re.escape(op)}\b", lower):
                    # Keep if any remaining (non-dropped) path still in sentence — conservative:
                    # if opcode alone is mentioned with a dropped path we already dropped above.
                    if any(dp.lower() in lower for dp in dropped_paths):
                        drop = True
                        break
        if not drop:
            kept.append(part)
    scrubbed = " ".join(kept).strip()
    return scrubbed


def scrub_after_minimize(
    original: dict | None,
    minimized: dict | None,
    rationale: str | None = None,
    assistant_text: str | None = None,
) -> dict[str, Any]:
    """Return scrubbed rationale/assistant_text plus dropped path metadata."""
    before = paths_in_program(original)
    after = paths_in_program(minimized)
    dropped_paths = before - after
    dropped_ops = opcodes_in_program(original) - opcodes_in_program(minimized)
    return {
        "rationale": scrub_text_for_dropped_refs(rationale, dropped_paths, dropped_ops),
        "assistant_text": scrub_text_for_dropped_refs(assistant_text, dropped_paths, dropped_ops),
        "droppedPaths": sorted(dropped_paths),
        "droppedOpcodes": sorted(dropped_ops),
    }
