import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

from app.llm_gemini import GeminiProposerBackend


def test_propose_extracts_propose_program_from_gemini_response():
    backend = GeminiProposerBackend.__new__(GeminiProposerBackend)
    backend._model = MagicMock()

    fn_part = SimpleNamespace(
        text=None,
        function_call=SimpleNamespace(
            name="propose_program",
            args={"ops": [{"op": "rename", "from": "/a", "to": "/b"}], "rationale": "fix"},
        ),
    )
    candidate = SimpleNamespace(content=SimpleNamespace(parts=[fn_part]))
    response = SimpleNamespace(candidates=[candidate], prompt_feedback=None)
    backend._model.generate_content_async = AsyncMock(return_value=response)

    program, text = asyncio.run(backend.propose("rename field a to b", {}, [], []))

    assert program is not None
    assert program["schemaVersion"] == "mendrscript/v1"
    assert program["ops"][0]["op"] == "rename"
    assert text == ""


def test_propose_returns_empty_when_no_candidates():
    backend = GeminiProposerBackend.__new__(GeminiProposerBackend)
    backend._model = MagicMock()
    backend._model.generate_content_async = AsyncMock(
        return_value=SimpleNamespace(candidates=[], prompt_feedback=None)
    )

    program, text = asyncio.run(backend.propose("hello", {}, [], []))

    assert program is None
    assert text == ""


def test_propose_handles_api_exception():
    backend = GeminiProposerBackend.__new__(GeminiProposerBackend)
    backend._model = MagicMock()
    backend._model.generate_content_async = AsyncMock(side_effect=RuntimeError("quota"))

    program, text = asyncio.run(backend.propose("hello", {}, [], []))

    assert program is None
    assert text == ""
