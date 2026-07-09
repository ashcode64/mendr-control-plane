from app.gemini_tools import normalize_program_args, propose_program_declaration


def test_propose_program_declaration_uses_parameters_key():
    decl = propose_program_declaration()
    assert decl["name"] == "propose_program"
    assert "parameters" in decl
    assert decl["parameters"]["type"] == "object"


def test_normalize_program_args_sets_schema_version():
    program = normalize_program_args({"ops": [], "rationale": "test"})
    assert program is not None
    assert program["schemaVersion"] == "mendrscript/v1"
