"""Tests for s₁ generation confidence and empty-simulate UNKNOWN."""
from app.generation_confidence import from_logprobs, resolve_s1
from app.critics_outcome import critic_ok


def test_from_logprobs_geometric_mean():
    assert from_logprobs([0.0, 0.0]) == 1.0
    assert from_logprobs({"meanLogProb": 0.0}) == 1.0
    assert from_logprobs(None) is None


def test_resolve_s1_priority():
    v, src = resolve_s1(logprobs=[-0.1, -0.1], cluster_frequency=0.9, verbalized=0.2)
    assert src == "token_logprobs"
    assert v is not None and v > 0.8

    v, src = resolve_s1(logprobs=None, cluster_frequency=0.7, verbalized=0.2)
    assert src == "cluster_frequency"
    assert v == 0.7

    v, src = resolve_s1(logprobs=None, cluster_frequency=None, verbalized=0.33)
    assert src == "verbalized"
    assert v == 0.33


def test_empty_simulate_is_unknown_not_success():
    assert critic_ok({"results": [], "passed": 0, "faulted": 0, "mismatched": 0}) is None
    assert critic_ok({"faulted": 0, "mismatched": 0, "passed": 0}) is None
    assert critic_ok({"results": [{"ok": True}], "passed": 1, "faulted": 0, "mismatched": 0}) is True
    assert critic_ok({"passed": 1, "faulted": 0, "mismatched": 2}) is False
    assert critic_ok({"valid": True}) is True
