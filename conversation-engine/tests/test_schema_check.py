"""Unit tests for fail-closed schema_check (Phase 0b)."""
from app.schema_check import validates_against_schema


def test_missing_schema_fails_closed():
    assert validates_against_schema({"results": [{"output": {"a": 1}}]}, None) is False


def test_missing_output_fails_closed():
    schema = {"type": "object", "properties": {"a": {"type": "integer"}}, "required": ["a"]}
    assert validates_against_schema({"results": [{}]}, schema) is False
    assert validates_against_schema({"results": [{"error": "boom"}]}, schema) is False


def test_valid_output_passes():
    schema = {"type": "object", "properties": {"a": {"type": "integer"}}, "required": ["a"]}
    sim = {"results": [{"output": {"a": 1}}]}
    assert validates_against_schema(sim, schema) is True


def test_invalid_output_fails():
    schema = {"type": "object", "properties": {"a": {"type": "integer"}}, "required": ["a"]}
    sim = {"results": [{"output": {"a": "x"}}]}
    assert validates_against_schema(sim, schema) is False
