"""Faithfulness / citation-lint eval harness for the zero-hallucination RCA narrative.

These tests are the abstention SLO: they prove that a fabricated path, a fabricated
edge/node id, an ungrounded root cause, an unsupported claim, or low confidence can
NEVER produce a rendered narrative — the pipeline abstains instead.
"""
import asyncio
from unittest.mock import AsyncMock

import app.rca_narrative as rca
from app.config import settings
from app.rca_narrative import build_enumerated_set, lint_selection, run_rca_narrative


# ── fixtures ──

CANDIDATES = {
    "found": True,
    "dependencies": [
        {"service": "payment-service", "depth": 1, "causal_confirmed": True},
        {"service": "ledger-service", "depth": 2, "causal_confirmed": False},
    ],
    "paths": [
        {"pathIndex": 0, "services": ["order-service", "payment-service"],
         "nodeIds": [1, 2], "edgeIds": [10], "causalConfirmed": True},
        {"pathIndex": 1, "services": ["order-service", "payment-service", "ledger-service"],
         "nodeIds": [1, 2, 3], "edgeIds": [10, 11], "causalConfirmed": False},
    ],
}
BLAST = {"affected": [{"service": "gateway", "depth": 1}]}
CYCLES = {"cycles": []}


class FakeMcp:
    """Stand-in tenant-bound McpClient returning canned tool responses by name."""

    def __init__(self, responses):
        self._responses = responses

    def for_tenant(self, _tenant):
        return self

    async def call_tool(self, name, arguments):
        val = self._responses.get(name)
        if isinstance(val, Exception):
            raise val
        return val if val is not None else {}


def _happy_responses(verify):
    return {
        "get_root_cause_candidates": CANDIDATES,
        "get_blast_radius": BLAST,
        "get_dependency_cycles": CYCLES,
        "verify_rca_claims": verify,
    }


def _run(coro):
    return asyncio.run(coro)


def _enable(monkeypatch):
    monkeypatch.setattr(settings, "rca_narrative_enabled", True)
    monkeypatch.setattr(settings, "rca_max_depth", 6)
    monkeypatch.setattr(settings, "rca_max_paths", 20)
    monkeypatch.setattr(settings, "rca_factuality_alpha", 0.05)
    monkeypatch.setattr(settings, "rca_min_confidence", 0.4)


# ── build_enumerated_set ──

def test_build_enumerated_set_collects_allowed_ids_and_services():
    enum = build_enumerated_set(CANDIDATES, BLAST, CYCLES)
    assert enum["allowedEdgeIds"] == {10, 11}
    assert enum["allowedNodeIds"] == {1, 2, 3}
    assert {"order-service", "payment-service", "ledger-service", "gateway"} <= enum["allowedServices"]
    assert len(enum["paths"]) == 2


# ── lint_selection ──

def _valid_selection():
    return {
        "pathIndex": 0,
        "rootCauseService": "payment-service",
        "claims": [{"type": "edge", "edgeId": 10}],
        "narrative": "payment-service is the root cause",
        "confidence": 0.9,
    }


def test_lint_accepts_grounded_selection():
    enum = build_enumerated_set(CANDIDATES, BLAST, CYCLES)
    ok, violations = lint_selection(_valid_selection(), enum)
    assert ok, violations


def test_lint_rejects_fabricated_edge_id():
    enum = build_enumerated_set(CANDIDATES, BLAST, CYCLES)
    sel = _valid_selection()
    sel["claims"] = [{"type": "edge", "edgeId": 999}]
    ok, violations = lint_selection(sel, enum)
    assert not ok
    assert any("edgeId" in v for v in violations)


def test_lint_rejects_out_of_range_path_index():
    enum = build_enumerated_set(CANDIDATES, BLAST, CYCLES)
    sel = _valid_selection()
    sel["pathIndex"] = 7
    ok, violations = lint_selection(sel, enum)
    assert not ok
    assert any("pathIndex" in v for v in violations)


def test_lint_rejects_root_cause_not_in_path():
    enum = build_enumerated_set(CANDIDATES, BLAST, CYCLES)
    sel = _valid_selection()
    sel["pathIndex"] = 0
    sel["rootCauseService"] = "ledger-service"  # not in path 0's services
    ok, violations = lint_selection(sel, enum)
    assert not ok
    assert any("rootCauseService" in v for v in violations)


def test_lint_rejects_ungrounded_narrative():
    enum = build_enumerated_set(CANDIDATES, BLAST, CYCLES)
    sel = _valid_selection()
    # cites a real edge, but one NOT on the chosen path (edge 11 is only on path 1)
    sel["claims"] = [{"type": "edge", "edgeId": 11}]
    ok, violations = lint_selection(sel, enum)
    assert not ok
    assert any("chosen path" in v for v in violations)


def test_lint_rejects_unknown_service_claim():
    enum = build_enumerated_set(CANDIDATES, BLAST, CYCLES)
    sel = _valid_selection()
    sel["claims"] = [
        {"type": "edge", "edgeId": 10},
        {"type": "causal", "sourceService": "phantom-service", "targetService": "order-service"},
    ]
    ok, violations = lint_selection(sel, enum)
    assert not ok
    assert any("phantom-service" in v for v in violations)


# ── _merge_claims ──

def test_merge_claims_dedupes_and_adds_path_edges():
    merged = rca._merge_claims([{"type": "edge", "edgeId": 10}], [10, 11])
    edge_ids = sorted(c["edgeId"] for c in merged if "edgeId" in c)
    assert edge_ids == [10, 11]  # 10 not duplicated, 11 added from path


# ── run_rca_narrative gate ──

def test_disabled_returns_not_enabled(monkeypatch):
    monkeypatch.setattr(settings, "rca_narrative_enabled", False)
    out = _run(run_rca_narrative({"contract_coords": {"service": "order-service"}}, FakeMcp({})))
    assert out == {"enabled": False}


def test_abstains_when_no_candidates(monkeypatch):
    _enable(monkeypatch)
    mcp = FakeMcp({"get_root_cause_candidates": {"found": False}})
    out = _run(run_rca_narrative({"contract_coords": {"service": "order-service"}}, mcp))
    assert out["abstained"] is True


def test_happy_path_renders_verified_narrative(monkeypatch):
    _enable(monkeypatch)
    verify = {"supportedCount": 1, "totalClaims": 1, "allSupported": True,
              "results": [{"supported": True, "kind": "edge",
                           "claim": {"type": "edge", "edgeId": 10},
                           "evidence": {"id": 10, "source": "order-service", "target": "payment-service"}}]}
    mcp = FakeMcp(_happy_responses(verify))
    monkeypatch.setattr(rca, "_select_via_llm", AsyncMock(return_value=_valid_selection()))

    out = _run(run_rca_narrative({"contract_coords": {"service": "order-service"}}, mcp))
    assert out["abstained"] is False
    assert out["narrative"]
    assert out["rootCauseService"] == "payment-service"
    assert out["citations"]
    assert out["audit"]["faithfulnessScore"] == 1.0


def test_abstains_when_claim_unsupported(monkeypatch):
    _enable(monkeypatch)
    verify = {"supportedCount": 0, "totalClaims": 1, "allSupported": False,
              "results": [{"supported": False, "kind": "edge",
                           "claim": {"type": "edge", "edgeId": 10}, "reason": "no current edge"}]}
    mcp = FakeMcp(_happy_responses(verify))
    monkeypatch.setattr(rca, "_select_via_llm", AsyncMock(return_value=_valid_selection()))

    out = _run(run_rca_narrative({"contract_coords": {"service": "order-service"}}, mcp))
    assert out["abstained"] is True
    assert out["audit"]["abstained"] is True


def test_abstains_when_lint_fails_on_fabricated_id(monkeypatch):
    _enable(monkeypatch)
    verify = {"supportedCount": 1, "totalClaims": 1, "allSupported": True, "results": []}
    mcp = FakeMcp(_happy_responses(verify))
    bad = _valid_selection()
    bad["claims"] = [{"type": "edge", "edgeId": 999}]  # fabricated
    monkeypatch.setattr(rca, "_select_via_llm", AsyncMock(return_value=bad))

    out = _run(run_rca_narrative({"contract_coords": {"service": "order-service"}}, mcp))
    assert out["abstained"] is True
    assert "lint" in out["reason"]


def test_abstains_when_confidence_below_threshold(monkeypatch):
    _enable(monkeypatch)
    verify = {"supportedCount": 1, "totalClaims": 1, "allSupported": True,
              "results": [{"supported": True, "kind": "edge",
                           "claim": {"type": "edge", "edgeId": 10}, "evidence": {"id": 10}}]}
    mcp = FakeMcp(_happy_responses(verify))
    low = _valid_selection()
    low["confidence"] = 0.1
    monkeypatch.setattr(rca, "_select_via_llm", AsyncMock(return_value=low))

    out = _run(run_rca_narrative({"contract_coords": {"service": "order-service"}}, mcp))
    assert out["abstained"] is True
    assert "confidence" in out["reason"]


def test_abstains_when_no_llm_selection(monkeypatch):
    _enable(monkeypatch)
    mcp = FakeMcp(_happy_responses({"supportedCount": 0, "totalClaims": 0, "allSupported": False}))
    monkeypatch.setattr(rca, "_select_via_llm", AsyncMock(return_value=None))

    out = _run(run_rca_narrative({"contract_coords": {"service": "order-service"}}, mcp))
    assert out["abstained"] is True
