"""Phase 6 embeddings — deterministic hash must match Java SignatureEmbedder dims."""
from app.embeddings import (
    DIM,
    canonical_signature_text,
    hash_embed,
    to_vector_literal,
)


def test_canonical_signature_text_stable():
    sig = {
        "category": "SCHEMA_MISMATCH",
        "change_type": "TYPE_COERCE",
        "json_path": "/order/amount",
        "expected_type": "integer",
        "observed_type": "string",
        "contract_coords": {
            "service": "Payment",
            "endpoint": "/pay",
            "direction": "REQUEST",
        },
    }
    a = canonical_signature_text(sig)
    b = canonical_signature_text(sig)
    assert a == b
    assert "type_coerce" in a
    assert "payment" in a


def test_hash_embed_dim_and_norm():
    vec = hash_embed("schema_mismatch|type_coerce|/amount")
    assert len(vec) == DIM
    assert abs(sum(v * v for v in vec) - 1.0) < 1e-5
    assert hash_embed("schema_mismatch|type_coerce|/amount") == vec


def test_to_vector_literal():
    lit = to_vector_literal([0.1, -0.2])
    assert lit == "[0.1,-0.2]"
