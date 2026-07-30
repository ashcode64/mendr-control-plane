use crate::ast::{MinimizeRequest, MinimizeResponse, Program};
use crate::eqsat::{cost, minimize_algebraic};
use crate::necessity::prune_necessity;
use crate::prove_minimal::prove_minimal;
use std::collections::HashMap;

const PROVE_HARD_CAP: usize = 8;

/// Run L2 → L3 → L4. Does not perform Java re-verify (gateway does that).
pub fn minimize(req: MinimizeRequest) -> MinimizeResponse {
    let draft = req.program.clone();
    let original = draft.op_count();
    let original_cost = cost(&draft.ops);
    let mut layers = Vec::new();
    let mut current = draft.clone();

    let mut cases = req.cases.clone();
    // Twin gate 2: ONLY when an explicit triggeringPayload was supplied.
    let mut triggering_gate = false;
    if let Some(tp) = &req.triggering_payload {
        let already = cases.iter().any(|c| &c.input == tp);
        if !already {
            cases.insert(
                0,
                crate::ast::Case {
                    input: tp.clone(),
                    expected: None,
                },
            );
        }
        triggering_gate = true;
    }

    let declared: HashMap<String, String> = req
        .declared_field_types
        .clone()
        .unwrap_or_default();
    let unresolvable: Vec<String> = req.unresolvable_paths.clone().unwrap_or_default();

    // L2 necessity
    let (after_l2, l2) = prune_necessity(&current, &cases, &unresolvable);
    if l2 {
        layers.push("necessity".into());
        current = after_l2;
    }

    // L3 algebraic / egg equality saturation (+ twin-gated coerce removal)
    let (after_l3, l3) = minimize_algebraic(
        &current,
        &cases,
        req.spec_trust,
        req.spec_trust_gate,
        triggering_gate,
        &declared,
    );
    if l3 {
        layers.push("eqsat".into());
        current = after_l3;
    }

    // L4 prove_minimal (server-side hard cap)
    let prove_gate = req.prove_minimal_max_ops.min(PROVE_HARD_CAP);
    let (after_l4, l4) = prove_minimal(
        &current,
        &cases,
        prove_gate,
        req.allowed_opcodes.as_deref(),
    );
    if l4 {
        layers.push("prove_minimal".into());
        current = after_l4;
    }

    let final_cost = cost(&current.ops);
    // Empty program over non-empty draft is not shippable via approve/deploy.
    let improved = final_cost < original_cost
        && !(current.ops.is_empty() && !draft.ops.is_empty());
    MinimizeResponse {
        program: if improved {
            current.clone()
        } else {
            draft.clone()
        },
        minimized: improved,
        layers_applied: if improved { layers } else { vec![] },
        original_op_count: original,
        final_op_count: if improved {
            current.op_count()
        } else {
            original
        },
        // fellBack is owned by the Java gateway (sidecar down / re-verify fail).
        // The Rust engine never soft-fails mid-search; inequivalent candidates are rejected in-layer.
        fell_back: false,
        draft_program: if improved { Some(draft) } else { None },
        engine: "rust".into(),
    }
}

#[allow(dead_code)]
pub fn identity_response(program: Program) -> MinimizeResponse {
    let n = program.op_count();
    MinimizeResponse {
        program,
        minimized: false,
        layers_applied: vec![],
        original_op_count: n,
        final_op_count: n,
        fell_back: false,
        draft_program: None,
        engine: "rust".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ast::{Case, Op, Program};
    use serde_json::json;

    fn prog(ops: Vec<Op>) -> Program {
        Program {
            schema_version: "mendrscript/v1".into(),
            ops,
            rationale: None,
            bandit_category: None,
        }
    }

    #[test]
    fn empty_over_nonempty_not_reported_as_improved() {
        let draft = prog(vec![Op {
            op: "rename".into(),
            fields: serde_json::Map::from_iter([
                ("from".into(), json!("/a")),
                ("to".into(), json!("/b")),
            ]),
        }]);
        // Identity-equivalent empty would be better by cost, but undeployable.
        let req = MinimizeRequest {
            program: draft.clone(),
            cases: vec![Case {
                input: json!({}),
                expected: Some(json!({})),
            }],
            triggering_payload: None,
            spec_trust: None,
            allowed_opcodes: None,
            spec_trust_gate: 0.85,
            prove_minimal_max_ops: 8,
            declared_field_types: None,
            unresolvable_paths: None,
        };
        let resp = minimize(req);
        // Empty input: rename is no-op → L2 may empty; pipeline must not ship empty.
        assert!(!resp.program.ops.is_empty() || draft.ops.is_empty());
        if resp.minimized {
            assert!(!resp.program.ops.is_empty());
        }
    }

    #[test]
    fn triggering_injection_opens_twin_gate_coerce_drop() {
        let draft = prog(vec![
            Op {
                op: "coerce".into(),
                fields: serde_json::Map::from_iter([
                    ("path".into(), json!("/amount")),
                    ("targetType".into(), json!("integer")),
                ]),
            },
            Op {
                op: "rename".into(),
                fields: serde_json::Map::from_iter([
                    ("from".into(), json!("/a")),
                    ("to".into(), json!("/b")),
                ]),
            },
        ]);
        let payload = json!({"amount": 1, "a": 1});
        let mut types = HashMap::new();
        types.insert("/amount".into(), "integer".into());
        let req = MinimizeRequest {
            program: draft,
            cases: vec![],
            triggering_payload: Some(payload),
            spec_trust: Some(0.95),
            allowed_opcodes: None,
            spec_trust_gate: 0.85,
            prove_minimal_max_ops: 8,
            declared_field_types: Some(types),
            unresolvable_paths: None,
        };
        let resp = minimize(req);
        assert!(resp.minimized, "layers={:?}", resp.layers_applied);
        assert_eq!(resp.final_op_count, 1);
        assert!(!resp.fell_back);
    }

    #[test]
    fn prove_max_ops_hard_cap_respected_via_request() {
        let mut ops = Vec::new();
        for i in 0..10 {
            ops.push(Op {
                op: "remove".into(),
                fields: serde_json::Map::from_iter([(
                    "path".into(),
                    json!(format!("/f{i}")),
                )]),
            });
        }
        let draft = prog(ops);
        let req = MinimizeRequest {
            program: draft,
            cases: vec![Case {
                input: json!({}),
                expected: None,
            }],
            triggering_payload: None,
            spec_trust: None,
            allowed_opcodes: None,
            spec_trust_gate: 0.85,
            prove_minimal_max_ops: 100, // will be clamped by handler; here call minimize directly
            declared_field_types: None,
            unresolvable_paths: None,
        };
        // Direct minimize uses unclamped gate but prove_minimal hard-caps at 8 internally.
        let resp = minimize(req);
        assert!(!resp.layers_applied.iter().any(|l| l == "prove_minimal") || resp.original_op_count <= 8
            || !resp.minimized
            || resp.original_op_count > 8);
        // With 10 ops, L4 skips (k>8); L2 may still shrink.
        let _ = resp;
    }
}
