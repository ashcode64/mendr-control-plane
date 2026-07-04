"""Lightweight action-screening guardrail for the conversation engine.

This is defense-in-depth on the *input* side: it flags obvious prompt-injection /
goal-hijack attempts so they can be logged and the model reminded of its rules. It is
NOT the security authority — the Java MendrScriptVerifier (protected-path scan, opcode
allowlist, post-conditions) is, and it runs again server-side at deploy. The point here
is to fail loudly on manipulation attempts rather than silently.
"""
import re

PROTECTED_FIELDS = ("authorization", "x-api-key", "credit_card_number", "internal_routing_id")

_INJECTION_PATTERNS = [
    re.compile(r"ignore (all|previous|prior) instructions", re.I),
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
