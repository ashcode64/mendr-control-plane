"""Gemini function declaration for MendrScript propose_program."""
from __future__ import annotations

from .prompts import PROPOSE_PROGRAM_TOOL, SCHEMA_VERSION


def propose_program_declaration() -> dict:
    """Convert Anthropic-style tool def to Gemini functionDeclaration."""
    schema = dict(PROPOSE_PROGRAM_TOOL.get("input_schema") or {})
    return {
        "name": PROPOSE_PROGRAM_TOOL["name"],
        "description": PROPOSE_PROGRAM_TOOL["description"],
        "parameters": schema,
    }


def normalize_program_args(args: dict | None) -> dict | None:
    if not args:
        return None
    program = dict(args)
    program.setdefault("schemaVersion", SCHEMA_VERSION)
    return program
