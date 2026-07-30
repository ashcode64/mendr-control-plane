"""Tests for twin-gate helpers and empty-ops merge guard."""
from app.minimize_helpers import (
    explicit_triggering_payload,
    merge_minimized_candidate,
    spec_trust,
)


def test_triggering_not_from_cases():
    assert explicit_triggering_payload({}, {}) is None
    assert explicit_triggering_payload({"triggeringPayload": {"a": 1}}, {}) == {"a": 1}


def test_spec_trust_from_sig():
    assert spec_trust({}, {"specTrust": 0.91}) == 0.91
    assert spec_trust({}, {"spec_trust": 0.88}) == 0.88


def test_merge_rejects_empty_over_nonempty():
    original = {"ops": [{"op": "rename", "from": "/a", "to": "/b"}]}
    report = {"minimized": True, "program": {"ops": []}}
    assert merge_minimized_candidate(original, report) is original
