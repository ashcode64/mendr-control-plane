"""Unit tests for True REx bandit_category coerce/abort."""
from app.bandit_category import coerce_or_abort, enforce_on_program, normalize


def test_normalize_enum_only():
    assert normalize("data_coercion") == "DATA_COERCION"
    assert normalize("MAGIC_FIX") is None


def test_missing_tag_aborts():
    assert coerce_or_abort(None, ["CORS"]) is None
    assert coerce_or_abort("  ", ["ROUTING"]) is None


def test_coerce_single_arm():
    assert coerce_or_abort("MAGIC_FIX", ["ROUTING"]) == "ROUTING"
    assert coerce_or_abort("MAGIC_FIX", ["ROUTING", "CORS"]) is None


def test_coerce_rejects_unsampled_valid():
    assert coerce_or_abort("CORS", ["DATA_COERCION"]) is None
    assert coerce_or_abort("CORS", ["CORS", "ROUTING"]) == "CORS"


def test_enforce_on_program():
    prog, cat = enforce_on_program(
        {"schemaVersion": "1", "ops": [], "bandit_category": "DATA_COERCION"},
        ["DATA_COERCION", "ADD_DEFAULT"],
    )
    assert cat == "DATA_COERCION"
    assert prog["bandit_category"] == "DATA_COERCION"

    aborted, _ = enforce_on_program(
        {"ops": [], "bandit_category": "MAGIC_FIX"},
        ["DATA_COERCION", "ADD_DEFAULT"],
    )
    assert aborted is None


def test_enforce_fills_assigned_when_llm_omits():
    prog, cat = enforce_on_program(
        {"ops": []},
        ["ROUTING", "CORS"],
        assigned_category="ROUTING",
    )
    assert cat == "ROUTING"
    assert prog["bandit_category"] == "ROUTING"


def test_enforce_aborts_when_missing_and_no_assigned():
    aborted, _ = enforce_on_program({"ops": []}, ["ROUTING", "CORS"])
    assert aborted is None
