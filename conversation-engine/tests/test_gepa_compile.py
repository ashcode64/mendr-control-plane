from app.gepa_compile import compile_prompt, mipro_fallback, _tip_from_critic


def test_mipro_fallback_distills_critics():
    out = mipro_fallback([
        {
            "critic_text": "Do not confuse rename with coerce here",
            "change_type": "TYPE_COERCE",
            "json_path": "/userId",
        },
        {"critic_text": "Authorization must stay protected", "change_type": "FIELD_RENAME"},
        {"change_type": "ADD_DEFAULT"},
    ])
    assert out["compiler"] == "mipro_fallback"
    assert "protected" in out["promptText"].lower() or "FIELD_RENAME" in out["promptText"]
    assert out["metrics"]["examples"] == 3


def test_compile_prompt_empty():
    out = compile_prompt([])
    assert out.get("error") == "empty_dataset"


def test_tip_from_critic_rename_coerce():
    tip = _tip_from_critic("rename vs coerce confusion", "TYPE_COERCE", "/age")
    assert "FIELD_RENAME" in tip
    assert "/age" in tip
