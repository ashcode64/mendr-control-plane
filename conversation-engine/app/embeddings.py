"""ErrorSignature embeddings for Phase 6 hybrid GraphRAG.

Prefer Gemini text-embedding-004 when GEMINI_API_KEY is set; otherwise a
deterministic 768-d hashing embedder that must stay compatible with the Java
SignatureEmbedder (token SHA-256 buckets + L2 normalize).
"""
from __future__ import annotations

import hashlib
import logging
import math
import re
from typing import Any

from .config import settings

logger = logging.getLogger("mendr.embeddings")

DIM = 768
_TOKEN_SPLIT = re.compile(r"[^a-z0-9_/.-]+")


def canonical_signature_text(sig: dict[str, Any] | None) -> str:
    if not sig:
        return ""
    parts = [
        _norm(sig.get("category")),
        _norm(sig.get("change_type")),
        _norm(sig.get("json_path")),
        _norm(sig.get("template_id")),
        _norm(sig.get("expected_type")),
        _norm(sig.get("observed_type")),
        _norm(sig.get("contract_ref")),
    ]
    coords = sig.get("contract_coords") or {}
    if isinstance(coords, dict):
        parts.extend([
            _norm(coords.get("service")),
            _norm(coords.get("endpoint")),
            _norm(coords.get("direction")),
        ])
    return "|".join(parts)


def hash_embed(text: str) -> list[float]:
    vec = [0.0] * DIM
    if not text:
        return _l2_normalize(vec)
    lowered = text.lower()
    for token in _TOKEN_SPLIT.split(lowered):
        if not token:
            continue
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        bucket = ((digest[0] << 8) | digest[1]) % DIM
        sign = 1.0 if (digest[2] & 1) == 0 else -1.0
        weight = 1.0 + (digest[3] / 255.0)
        vec[bucket] += sign * weight
    full = hashlib.sha256(lowered.encode("utf-8")).digest()
    for i in range(16):
        idx = ((full[i] << 4) | (full[(i + 1) % 16] & 0x0F)) % DIM
        vec[idx] += 1.0 if (full[(i + 2) % 16] & 1) == 0 else -1.0
    return _l2_normalize(vec)


def embed_signature(sig: dict[str, Any] | None, *, prefer_gemini: bool = True) -> list[float]:
    text = canonical_signature_text(sig)
    if prefer_gemini and settings.gemini_api_key:
        try:
            return gemini_embed(text)
        except Exception as e:
            logger.warning("gemini embed failed, using hash fallback: %s", e)
    return hash_embed(text)


def gemini_embed(text: str) -> list[float]:
    """Call Gemini text-embedding-004; pad/truncate to DIM."""
    import google.generativeai as genai

    genai.configure(api_key=settings.gemini_api_key)
    result = genai.embed_content(
        model="models/text-embedding-004",
        content=text or " ",
        task_type="retrieval_document",
    )
    values = list(result["embedding"]) if isinstance(result, dict) else list(result.embedding)
    if len(values) >= DIM:
        values = values[:DIM]
    else:
        values = values + [0.0] * (DIM - len(values))
    return _l2_normalize(values)


def to_vector_literal(vec: list[float]) -> str:
    return "[" + ",".join(str(float(v)) for v in vec) + "]"


def _norm(o: Any) -> str:
    if o is None:
        return ""
    return str(o).strip().lower()


def _l2_normalize(vec: list[float]) -> list[float]:
    s = sum(v * v for v in vec)
    if s <= 1e-12:
        out = [0.0] * len(vec)
        out[0] = 1.0
        return out
    inv = 1.0 / math.sqrt(s)
    return [v * inv for v in vec]
