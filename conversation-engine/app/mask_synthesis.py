"""Offline LLM-guided-once mask synthesis (DeepParse pattern).

When Drain3 creates new/low-similarity clusters, call this job asynchronously
to propose additional regex masking instructions. Humans review once; masks
are then applied deterministically forever. Never invoke on the hot path.
"""
from __future__ import annotations

import json
import logging
import re
from typing import Any

logger = logging.getLogger("mendr.mask_synthesis")

# Starter seed masks (same as template_miner) — synthesis suggests ADDITIONS only.
_SEED_NAMES = {"UUID", "IPV4", "HEX", "NUM", "QUOTED", "ISO_TS"}


def propose_masks_from_skeletons(
    skeletons: list[str],
    llm_complete: Any | None = None,
) -> list[dict[str, str]]:
    """Return proposed {name, regex_pattern} masks for variable tokens in skeletons.

    If ``llm_complete`` is None, uses a conservative heuristic (email, path-ish tokens).
    When provided, ``llm_complete(prompt: str) -> str`` should return JSON list.
    """
    if not skeletons:
        return []

    if llm_complete is not None:
        prompt = (
            "Given these error-message templates (variables already as <*> or <:MASK:>), "
            "propose up to 5 additional Python regex patterns with short NAME labels "
            "to mask remaining high-cardinality tokens. Return JSON array of "
            '{"name":"...","regex_pattern":"..."}. Templates:\n'
            + "\n".join(skeletons[:20])
        )
        try:
            raw = llm_complete(prompt)
            parsed = json.loads(raw)
            out = []
            for item in parsed if isinstance(parsed, list) else []:
                name = str(item.get("name", "")).upper()
                pat = item.get("regex_pattern")
                if not name or not pat or name in _SEED_NAMES:
                    continue
                try:
                    re.compile(pat)
                except re.error:
                    continue
                out.append({"name": name, "regex_pattern": pat})
            return out
        except Exception as e:
            logger.warning("LLM mask synthesis failed, falling back to heuristics: %s", e)

    # Heuristic additions
    proposals = []
    joined = "\n".join(skeletons)
    if re.search(r"\b[\w.+-]+@[\w.-]+\.\w+\b", joined):
        proposals.append({
            "name": "EMAIL",
            "regex_pattern": r"\b[\w.+-]+@[\w.-]+\.\w+\b",
        })
    if re.search(r"/[a-zA-Z0-9_\-./]{8,}", joined):
        proposals.append({
            "name": "PATHSEG",
            "regex_pattern": r"/[a-zA-Z0-9_\-./]{8,}",
        })
    return proposals
