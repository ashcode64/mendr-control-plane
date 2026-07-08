"""LLM proposer: turns a natural-language request into a MendrScript AST via strict
tool-use. Strict tool-use is constrained decoding — the model's output is forced to the
propose_program schema at inference time.

Provider is selected via LLM_PROVIDER (anthropic | gemini). If no API key is configured
for the active provider, a deterministic no-model stub is used.
"""
from __future__ import annotations

from .config import settings
from .llm_anthropic import AnthropicProposerBackend
from .llm_gemini import GeminiProposerBackend


def _not_configured_message() -> str:
    if settings.llm_provider == "gemini":
        return ("LLM is not configured (set GEMINI_API_KEY with LLM_PROVIDER=gemini). "
                "I can verify and simulate a program you paste, but I cannot synthesize one here.")
    return ("LLM is not configured (set ANTHROPIC_API_KEY with LLM_PROVIDER=anthropic). "
            "I can verify and simulate a program you paste, but I cannot synthesize one here.")


class Proposer:
    def __init__(self):
        if settings.llm_provider == "gemini":
            self._backend = GeminiProposerBackend()
        else:
            self._backend = AnthropicProposerBackend()

    @property
    def enabled(self) -> bool:
        return self._backend.enabled

    async def propose(
        self,
        user_message: str,
        context: dict,
        prior_errors: list[str],
        prior_turns: list[dict] | None = None,
    ) -> tuple[dict | None, str]:
        if not self._backend.enabled:
            return None, _not_configured_message()

        program, text = await self._backend.propose(
            user_message, context, prior_errors, prior_turns)
        if program is None and not text:
            return None, _not_configured_message()
        return program, text
