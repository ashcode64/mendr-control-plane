"""s₁ generation confidence: prefer token logprobs, else cluster / verbalized proxy.

Matches plan Layer-1: exp(mean log p) over changed tokens when providers expose
logprobs; otherwise leave None so AIS can apply cluster-frequency / verbalized.
"""
from __future__ import annotations

import math
from typing import Any


def from_logprobs(logprobs: Any) -> float | None:
    """Geometric mean of token probabilities = exp(mean log p). Returns None if unusable."""
    values: list[float] = []
    if isinstance(logprobs, (int, float)):
        # Already a mean log-prob (≤ 0) or a probability in (0,1].
        v = float(logprobs)
        if v <= 0.0:
            return _clamp01(math.exp(v))
        if 0.0 < v <= 1.0:
            return _clamp01(v)
        return None
    if isinstance(logprobs, dict):
        if isinstance(logprobs.get("meanLogProb"), (int, float)):
            return _clamp01(math.exp(float(logprobs["meanLogProb"])))
        if isinstance(logprobs.get("mean_logprob"), (int, float)):
            return _clamp01(math.exp(float(logprobs["mean_logprob"])))
        inner = logprobs.get("tokenLogprobs") or logprobs.get("logprobs") or logprobs.get("tokens")
        return from_logprobs(inner)
    if isinstance(logprobs, (list, tuple)):
        for item in logprobs:
            if isinstance(item, (int, float)):
                values.append(float(item))
            elif isinstance(item, dict):
                lp = item.get("logprob", item.get("log_prob", item.get("lp")))
                if isinstance(lp, (int, float)):
                    values.append(float(lp))
    if not values:
        return None
    mean_lp = sum(values) / len(values)
    return _clamp01(math.exp(mean_lp))


def extract_from_response(resp: Any) -> float | None:
    """Best-effort extract from Anthropic/Gemini SDK response objects or dicts."""
    if resp is None:
        return None
    # Dict-shaped provider payloads
    if isinstance(resp, dict):
        for key in ("tokenLogprobs", "logprobs", "changedSpanLogprobs", "generationLogprobs"):
            got = from_logprobs(resp.get(key))
            if got is not None:
                return got
        content = resp.get("content")
        if isinstance(content, list):
            for block in content:
                got = extract_from_response(block)
                if got is not None:
                    return got
        return None
    # SDK content blocks may expose .logprobs
    logprobs = getattr(resp, "logprobs", None)
    if logprobs is not None:
        got = from_logprobs(logprobs)
        if got is not None:
            return got
        content = getattr(logprobs, "content", None) or getattr(logprobs, "tokens", None)
        got = from_logprobs(content)
        if got is not None:
            return got
    content = getattr(resp, "content", None)
    if isinstance(content, (list, tuple)):
        for block in content:
            got = extract_from_response(block)
            if got is not None:
                return got
    return None


def resolve_s1(
    *,
    logprobs: Any = None,
    cluster_frequency: float | None = None,
    verbalized: float | None = None,
) -> tuple[float | None, str]:
    """
    Priority: token logprobs → cluster frequency (n≥2) → verbalized LLM score.
    Returns (score_or_None, source).
    """
    from_lp = from_logprobs(logprobs)
    if from_lp is not None:
        return from_lp, "token_logprobs"
    if isinstance(cluster_frequency, (int, float)) and float(cluster_frequency) >= 0.0:
        return _clamp01(float(cluster_frequency)), "cluster_frequency"
    if isinstance(verbalized, (int, float)):
        return _clamp01(float(verbalized)), "verbalized"
    return None, "none"


def _clamp01(v: float) -> float:
    if math.isnan(v) or math.isinf(v):
        return 0.5
    return max(0.0, min(1.0, v))
