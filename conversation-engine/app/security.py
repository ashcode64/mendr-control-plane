"""Lightweight action-screening guardrail for the conversation engine.

This is defense-in-depth on the *input* side: it flags obvious prompt-injection /
goal-hijack attempts so they can be logged and the model reminded of its rules. It is
NOT the security authority — the Java MendrScriptVerifier (protected-path scan, opcode
allowlist, post-conditions) is, and it runs again server-side at deploy. The point here
is to fail loudly on manipulation attempts rather than silently.
"""
from __future__ import annotations

import re

PROTECTED_FIELDS = ("authorization", "x-api-key", "credit_card_number", "internal_routing_id")

_INJECTION_PATTERNS = [
    # Matches the canonical variants: "ignore previous instructions",
    # "ignore all previous instructions", "ignore the above instructions", etc.
    re.compile(r"ignore\s+(all|any|the|previous|prior|earlier|above).{0,30}instructions", re.I),
    re.compile(r"disregard (the )?(system|rules|guardrails)", re.I),
    re.compile(r"you are now", re.I),
    re.compile(r"(reveal|print|show).{0,20}(system prompt|instructions)", re.I),
    re.compile(r"\b(deploy|apply|push|ship)\b.{0,30}\b(directly|now|without approval)\b", re.I),
]


def screen_user_input(text: str) -> list[str]:
    """Return a list of flags for suspicious user input (empty == clean)."""
    flags = []
    if not text:
        return flags
    for pat in _INJECTION_PATTERNS:
        if pat.search(text):
            flags.append(f"possible prompt-injection: matched /{pat.pattern}/")
    low = text.lower()
    for f in PROTECTED_FIELDS:
        if f in low:
            flags.append(f"mentions protected field '{f}' — programs touching it will be rejected")
    return flags


# Secret-shaped tokens we must never echo back in assistant output (defense on the
# OUTPUT side — OWASP LLM02 Sensitive Information Disclosure). The engine has no
# access to real secrets, but a prompt-injection attempt could try to get the model
# to reflect a value the user pasted; scrub it rather than relay it.
_SECRET_PATTERNS = [
    re.compile(r"\bsk-[A-Za-z0-9]{16,}\b"),                 # api-key style
    re.compile(r"\bmendr_[A-Za-z0-9._-]{12,}\b"),           # mendr edge/api keys
    re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9._-]+\b"),  # JWT
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._-]{16,}\b"),    # bearer tokens
]

_REDACTION = "[redacted]"


def scrub_output(text: str | None) -> str | None:
    """Redact anything that looks like a credential from assistant-facing text."""
    if not text:
        return text
    scrubbed = text
    for pat in _SECRET_PATTERNS:
        scrubbed = pat.sub(_REDACTION, scrubbed)
    return scrubbed
