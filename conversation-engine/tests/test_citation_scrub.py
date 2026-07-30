"""Tests for post-minimize citation scrubbing."""
from app.citation_scrub import scrub_after_minimize, scrub_text_for_dropped_refs


def test_scrub_drops_sentence_with_pruned_path():
    text = "Rename /a to /b. Also default /unused. Done."
    out = scrub_text_for_dropped_refs(text, {"/unused"}, set())
    assert "/unused" not in out
    assert "/a" in out or "Rename" in out


def test_scrub_after_minimize_metadata():
    original = {
        "ops": [
            {"op": "rename", "from": "/a", "to": "/b"},
            {"op": "default", "path": "/gone", "value": "x"},
        ]
    }
    minimized = {"ops": [{"op": "rename", "from": "/a", "to": "/b"}]}
    scrub = scrub_after_minimize(
        original,
        minimized,
        rationale="Apply rename /a and default /gone.",
        assistant_text="We should also touch /gone.",
    )
    assert "/gone" in scrub["droppedPaths"]
    assert "/gone" not in scrub["rationale"]
    assert "/gone" not in scrub["assistant_text"]
