"""Unit tests for Drain3 masking + fallback template mining (no Drain3 required)."""
from app.template_miner import TemplateMiner, apply_masks, stable_template_id
from app.mask_synthesis import propose_masks_from_skeletons


def test_apply_masks_collapses_uuid_and_number():
    msg = "user 550e8400-e29b-41d4-a716-446655440000 got status 500"
    masked, names = apply_masks(msg)
    assert "UUID" in names
    assert "NUM" in names
    assert "550e8400" not in masked
    assert "<:UUID:>" in masked


def test_iso_timestamp_masked_before_num():
    msg = "failed at 2024-01-15T10:30:00Z with code 42"
    masked, names = apply_masks(msg)
    assert "ISO_TS" in names
    assert "<:ISO_TS:>" in masked
    assert "2024-01-15" not in masked
    assert "NUM" in names  # the trailing 42


def test_stable_template_id_is_deterministic():
    a = stable_template_id("Cannot deserialize <*> from <*>", None)
    b = stable_template_id("Cannot deserialize <*> from <*>", None)
    assert a == b
    assert a.startswith("tmpl_")


def test_fallback_miner_without_drain3():
    miner = TemplateMiner()
    # Force fallback path
    miner._miner = None
    result = miner.mine('Cannot deserialize value of type int from String "25"')
    assert result is not None
    assert result.template_id
    assert "deserialize" in result.skeleton.lower() or "<:" in result.skeleton


def test_mask_synthesis_heuristic_email():
    props = propose_masks_from_skeletons([
        "user alice@example.com failed auth",
        "user bob@example.org failed auth",
    ])
    assert any(p["name"] == "EMAIL" for p in props)
