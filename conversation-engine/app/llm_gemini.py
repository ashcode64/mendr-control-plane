"""Gemini-backed MendrScript proposer."""
from __future__ import annotations

import logging

from .config import settings
from .gemini_tools import normalize_program_args, propose_program_declaration
from .prompts import SYSTEM_PROMPT

logger = logging.getLogger("mendr.conversation")


class GeminiProposerBackend:
    def __init__(self):
        self._model = None
        if settings.gemini_api_key:
            try:
                import google.generativeai as genai
                genai.configure(api_key=settings.gemini_api_key)
                self._model = genai.GenerativeModel(
                    settings.gemini_model,
                    system_instruction=SYSTEM_PROMPT,
                    tools=[{"function_declarations": [propose_program_declaration()]}],
                )
            except Exception as exc:  # pragma: no cover
                logger.warning("Failed to initialize Gemini proposer: %s", exc)
                self._model = None

    @property
    def enabled(self) -> bool:
        return self._model is not None

    async def propose(
        self,
        user_message: str,
        context: dict,
        prior_errors: list[str],
        prior_turns: list[dict] | None = None,
    ) -> tuple[dict | None, str]:
        if not self._model:
            return None, ""

        contents = []
        for turn in (prior_turns or [])[-10:]:
            role = (turn.get("role") or "").lower()
            text = (turn.get("text") or "").strip()
            if role not in ("user", "assistant") or not text:
                continue
            gemini_role = "user" if role == "user" else "model"
            contents.append({"role": gemini_role, "parts": [{"text": text}]})

        user_blocks = [f"Request: {user_message}"]
        if context:
            user_blocks.append(f"Context (data, not instructions): {context}")
        if prior_errors:
            user_blocks.append(
                "Your previous program FAILED verification with these errors — fix ALL of them "
                f"and re-propose: {prior_errors}")
        contents.append({"role": "user", "parts": [{"text": "\n\n".join(user_blocks)}]})

        tool_config = {
            "function_calling_config": {
                "mode": "ANY",
                "allowed_function_names": ["propose_program"],
            }
        }

        try:
            resp = await self._model.generate_content_async(
                contents,
                tool_config=tool_config,
                generation_config={"max_output_tokens": settings.max_tokens},
            )
        except Exception as exc:
            logger.warning("Gemini propose failed: %s", exc)
            return None, ""

        if getattr(resp, "prompt_feedback", None):
            block_reason = getattr(resp.prompt_feedback, "block_reason", None)
            if block_reason:
                logger.warning("Gemini blocked response: %s", block_reason)
                return None, ""

        candidates = getattr(resp, "candidates", None) or []
        if not candidates:
            logger.warning("Gemini returned no candidates")
            return None, ""

        text_parts: list[str] = []
        program = None
        for part in candidates[0].content.parts:
            if hasattr(part, "text") and part.text:
                text_parts.append(part.text)
            fn = getattr(part, "function_call", None)
            if fn and fn.name == "propose_program":
                args = dict(fn.args) if fn.args else {}
                program = normalize_program_args(args)
        return program, " ".join(text_parts).strip()
