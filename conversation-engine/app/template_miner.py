"""Drain3-backed template miner for UNKNOWN / opaque error strings.

Hot path: regex masking → Drain3 match/add → template_id + variable slots.
Offline LLM mask synthesis is a separate async job (not invoked here).
"""
from __future__ import annotations

import hashlib
import logging
import re
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger("mendr.template_miner")

# Pre-Drain masks — collapses vocabulary explosion before clustering.
# Order matters: ISO timestamps / UUIDs / IPs / hex BEFORE generic NUM,
# otherwise digits inside timestamps are shredded into <:NUM:> tokens.
_MASK_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("UUID", re.compile(
        r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")),
    ("ISO_TS", re.compile(
        r"\b\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?\b")),
    ("IPV4", re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")),
    ("HEX", re.compile(r"\b0x[0-9a-fA-F]+\b")),
    ("QUOTED", re.compile(r"'[^']*'|\"[^\"]*\"")),
    ("NUM", re.compile(r"\b\d+(?:\.\d+)?\b")),
]


@dataclass
class MineResult:
    template_id: str
    skeleton: str
    variables: list[str] = field(default_factory=list)
    mask_names: list[str] = field(default_factory=list)
    is_new: bool = False
    low_similarity: bool = False


class TemplateMiner:
    """Lazy Drain3 wrapper — import fails gracefully if drain3 is missing."""

    def __init__(self, sim_th: float = 0.4):
        self.sim_th = sim_th
        self._miner = None
        self._init_error: str | None = None
        self._try_init()

    def _try_init(self) -> None:
        try:
            from drain3 import TemplateMiner as DrainTemplateMiner
            from drain3.template_miner_config import TemplateMinerConfig

            cfg = TemplateMinerConfig()
            # Prefer file-less defaults; pre-masking is applied in apply_masks() before Drain.
            try:
                cfg.drain_sim_th = self.sim_th
            except Exception:
                pass
            self._miner = DrainTemplateMiner(config=cfg)
        except Exception as e:
            self._init_error = str(e)
            logger.warning("Drain3 unavailable (%s) — using regex fallback miner", e)
            self._miner = None

    @property
    def available(self) -> bool:
        return self._miner is not None

    def mine(self, message: str) -> MineResult | None:
        if not message or not message.strip():
            return None
        masked, mask_names = apply_masks(message)
        if self._miner is not None:
            return self._mine_drain(masked, mask_names)
        return self._mine_fallback(masked, mask_names)

    def _mine_drain(self, masked: str, mask_names: list[str]) -> MineResult:
        result = self._miner.add_log_message(masked)
        cluster = result.get("cluster") if isinstance(result, dict) else None
        template = ""
        cluster_id = None
        change_type = None
        if isinstance(result, dict):
            template = str(result.get("template_mined") or result.get("template") or masked)
            change_type = result.get("change_type")
            if cluster is not None:
                cluster_id = getattr(cluster, "cluster_id", None)
                if hasattr(cluster, "get_template"):
                    template = cluster.get_template()
        skeleton = template if template else masked
        tid = stable_template_id(skeleton, cluster_id)
        variables = extract_variables(masked, skeleton)
        is_new = change_type in ("cluster_created", "cluster_template_changed") or change_type is None and not variables
        # Treat brand-new clusters as potential post-deploy signal
        low_sim = change_type == "cluster_created"
        return MineResult(
            template_id=tid,
            skeleton=skeleton,
            variables=variables,
            mask_names=mask_names,
            is_new=bool(is_new) or low_sim,
            low_similarity=low_sim,
        )

    def _mine_fallback(self, masked: str, mask_names: list[str]) -> MineResult:
        # Lightweight skeleton: collapse consecutive <*>-like placeholders already masked
        skeleton = re.sub(r"(<:[^:]+:>){2,}", r"\1", masked)
        skeleton = re.sub(r"\s+", " ", skeleton).strip()
        tid = stable_template_id(skeleton, None)
        return MineResult(
            template_id=tid,
            skeleton=skeleton,
            variables=[],
            mask_names=mask_names,
            is_new=False,
            low_similarity=False,
        )


def apply_masks(message: str) -> tuple[str, list[str]]:
    names: list[str] = []
    out = message
    for name, pattern in _MASK_PATTERNS:
        if pattern.search(out):
            names.append(name)
            out = pattern.sub(f"<:{name}:>", out)
    return out, names


def stable_template_id(skeleton: str, cluster_id: Any) -> str:
    if cluster_id is not None:
        return f"drain_{cluster_id}"
    digest = hashlib.sha256(skeleton.encode("utf-8")).hexdigest()[:16]
    return f"tmpl_{digest}"


def extract_variables(masked: str, skeleton: str) -> list[str]:
    """Best-effort: tokens in masked that are replaced by <*> in skeleton."""
    # Drain uses <*> ; our masks use <:NAME:>
    sk_tokens = skeleton.split()
    msg_tokens = masked.split()
    vars_: list[str] = []
    if len(sk_tokens) != len(msg_tokens):
        return vars_
    for s, m in zip(sk_tokens, msg_tokens):
        if s in ("<*>",) or (s.startswith("<:") and s.endswith(":>")):
            if m not in ("<*>",) and not (m.startswith("<:") and m.endswith(":>")):
                vars_.append(m)
    return vars_
