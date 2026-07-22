"""Fail-closed contract validation for Causal Intervention Localization (Phase 0b)."""
from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("mendr.schema_check")

try:
    import jsonschema
except ImportError:  # pragma: no cover
    jsonschema = None  # type: ignore


def validates_against_schema(
    simulation_result: dict | None,
    target_schema: dict | None,
) -> bool:
    """Return True only when simulation output validates against the contract schema.

    Fail-closed: missing schema, missing output, malformed schema, or validation
    error → False (never claim causal verification without an oracle).
    """
    if not target_schema or not isinstance(target_schema, dict):
        return False
    if jsonschema is None:
        logger.debug("jsonschema not installed — fail closed")
        return False
    if not simulation_result or not isinstance(simulation_result, dict):
        return False

    output = _extract_output(simulation_result)
    if output is None:
        return False
    try:
        jsonschema.validate(instance=output, schema=target_schema)
        return True
    except jsonschema.ValidationError:
        return False
    except jsonschema.SchemaError:
        return False
    except Exception as e:  # pragma: no cover
        logger.debug("schema check failed closed: %s", e)
        return False


def _extract_output(simulation_result: dict) -> Any:
    results = simulation_result.get("results")
    if isinstance(results, list) and results:
        first = results[0]
        if isinstance(first, dict):
            if first.get("error") or first.get("ok") is False:
                return None
            if "output" in first:
                return first.get("output")
            if "result" in first:
                return first.get("result")
    # Alternate gateway shapes
    if "output" in simulation_result:
        return simulation_result.get("output")
    if simulation_result.get("ok") is False or simulation_result.get("error"):
        return None
    return None
