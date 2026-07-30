//! L3 — Equality saturation over MendrScript op sequences via `egg`.
//!
//! Strategy:
//! 1. Encode programs as cons-lists over a structured [`MsLang`] (not opaque bags).
//! 2. Saturate with `egg::rewrite!` rules (compose, cancel, shadow, dead-default).
//! 3. Seed twin-gated / commute variants into the same e-class (context-sensitive;
//!    not expressible as pure egg patterns).
//! 4. Extract once with [`MendrCost`] `(opCount, valueMutating)`.

use crate::ast::{Op, Program};
use crate::interpret::programs_equivalent;
use egg::{
    rewrite, CostFunction, EGraph, Extractor, Id, RecExpr, Rewrite, Runner, Symbol,
    define_language,
};
use serde_json::Value;
use std::collections::HashMap;

define_language! {
    pub enum MsLang {
        "nil" = Nil,
        "cons" = Cons([Id; 2]),
        "rename" = Rename([Id; 2]),
        "move" = Move([Id; 2]),
        "wrap" = Wrap([Id; 1]),
        "unwrap" = Unwrap([Id; 1]),
        "wrap_array" = WrapArray([Id; 1]),
        "unwrap_array" = UnwrapArray([Id; 1]),
        "coerce" = Coerce([Id; 2]),
        // path, JSON-serialized value, on-mode
        "default" = Default([Id; 3]),
        "remove" = Remove([Id; 1]),
        // Opaque op: single symbol holding stable JSON fingerprint + payload key.
        "raw" = Raw([Id; 1]),
        Symbol(Symbol),
    }
}

pub fn minimize_algebraic(
    program: &Program,
    cases: &[crate::ast::Case],
    spec_trust: Option<f64>,
    spec_trust_gate: f64,
    // Twin gate 2: explicit triggering payload was supplied.
    triggering_gate: bool,
    declared_field_types: &HashMap<String, String>,
) -> (Program, bool) {
    let twin_gates =
        triggering_gate && spec_trust.unwrap_or(0.0) >= spec_trust_gate && !declared_field_types.is_empty();

    let extracted = saturate_and_extract(&program.ops, twin_gates, declared_field_types);
    let cand = program.with_ops(extracted);
    if !cases.is_empty() && !programs_equivalent(program, &cand, cases) {
        return (program.clone(), false);
    }
    let improved = cost(&cand.ops) < cost(&program.ops);
    (cand, improved)
}

fn saturate_and_extract(
    ops: &[Op],
    twin_gates: bool,
    declared_field_types: &HashMap<String, String>,
) -> Vec<Op> {
    if ops.is_empty() {
        return vec![];
    }

    let mut registry: HashMap<Symbol, Op> = HashMap::new();
    let mut egraph: EGraph<MsLang, ()> = Default::default();
    let root0 = add_program(&mut egraph, ops, &mut registry);

    // Context-sensitive seeds (commute + twin-gated coerce) — union into root class.
    for seed in context_seeds(ops, twin_gates, declared_field_types) {
        let id = add_program(&mut egraph, &seed, &mut registry);
        egraph.union(root0, id);
    }
    egraph.rebuild();

    let rules = mendr_rules();
    let runner = Runner::default()
        .with_egraph(egraph)
        .with_iter_limit(12)
        .with_node_limit(10_000)
        .run(&rules);
    let egraph = runner.egraph;
    let root = egraph.find(root0);

    let extractor = Extractor::new(&egraph, MendrCost);
    let (_c, best_expr) = extractor.find_best(root);
    decode_expr(&best_expr, &registry).unwrap_or_else(|| ops.to_vec())
}

fn context_seeds(
    ops: &[Op],
    twin_gates: bool,
    declared_field_types: &HashMap<String, String>,
) -> Vec<Vec<Op>> {
    let mut out = Vec::new();
    if let Some(v) = commute_disjoint_for_cancel(ops) {
        out.push(v);
    }
    if twin_gates {
        if let Some(v) = schema_gated_coerce_removal(ops, declared_field_types) {
            out.push(v);
        }
    }
    out
}

fn mendr_rules() -> Vec<Rewrite<MsLang, ()>> {
    vec![
        rewrite!("compose-rename";
            "(cons (rename ?a ?b) (cons (rename ?b ?c) ?rest))"
            => "(cons (rename ?a ?c) ?rest)"),
        rewrite!("compose-move";
            "(cons (move ?a ?b) (cons (move ?b ?c) ?rest))"
            => "(cons (move ?a ?c) ?rest)"),
        rewrite!("cancel-wrap";
            "(cons (wrap ?k) (cons (unwrap ?k) ?rest))" => "?rest"),
        rewrite!("cancel-wrap-array";
            "(cons (wrap_array ?p) (cons (unwrap_array ?p) ?rest))" => "?rest"),
        rewrite!("shadow-coerce";
            "(cons (coerce ?p ?t1) (cons (coerce ?p ?t2) ?rest))"
            => "(cons (coerce ?p ?t2) ?rest)"),
        rewrite!("dead-default-remove";
            "(cons (default ?p ?v ?on) (cons (remove ?p) ?rest))"
            => "(cons (remove ?p) ?rest)"),
    ]
}

/// Lexicographic cost: fewer ops, then fewer value-mutating ops.
struct MendrCost;

impl CostFunction<MsLang> for MendrCost {
    type Cost = (usize, usize);

    fn cost<C>(&mut self, enode: &MsLang, mut costs: C) -> Self::Cost
    where
        C: FnMut(Id) -> Self::Cost,
    {
        match enode {
            MsLang::Nil => (0, 0),
            MsLang::Cons([h, t]) => {
                let (ho, hm) = costs(*h);
                let (to, tm) = costs(*t);
                // Head contributes op-cost; Symbol/raw payload leaves contribute 0 via head class.
                (ho + to, hm + tm)
            }
            MsLang::Rename(_) | MsLang::Move(_) | MsLang::Wrap(_) | MsLang::Unwrap(_)
            | MsLang::WrapArray(_) | MsLang::UnwrapArray(_) | MsLang::Default(_)
            | MsLang::Remove(_) => (1, 0),
            MsLang::Coerce(_) => (1, 1),
            MsLang::Raw(_) => (1, 0), // opaque opcode; mutating unknown — don't inflate
            MsLang::Symbol(_) => (0, 0),
        }
    }
}

fn add_program(
    egraph: &mut EGraph<MsLang, ()>,
    ops: &[Op],
    registry: &mut HashMap<Symbol, Op>,
) -> Id {
    let expr = encode_program(ops, registry);
    egraph.add_expr(&expr)
}

fn encode_program(ops: &[Op], registry: &mut HashMap<Symbol, Op>) -> RecExpr<MsLang> {
    let mut expr = RecExpr::default();
    let nil = expr.add(MsLang::Nil);
    let mut cur = nil;
    for op in ops.iter().rev() {
        let head = encode_op(&mut expr, op, registry);
        cur = expr.add(MsLang::Cons([head, cur]));
    }
    expr
}

fn encode_op(expr: &mut RecExpr<MsLang>, op: &Op, registry: &mut HashMap<Symbol, Op>) -> Id {
    match op.op.as_str() {
        "rename" => {
            let a = sym(expr, op.get_str("from").unwrap_or(""));
            let b = sym(expr, op.get_str("to").unwrap_or(""));
            expr.add(MsLang::Rename([a, b]))
        }
        "move" => {
            let a = sym(expr, op.get_str("from").unwrap_or(""));
            let b = sym(expr, op.get_str("to").unwrap_or(""));
            expr.add(MsLang::Move([a, b]))
        }
        "wrap" => {
            let k = sym(expr, op.get_str("key").unwrap_or(""));
            expr.add(MsLang::Wrap([k]))
        }
        "unwrap" => {
            let k = sym(expr, op.get_str("key").unwrap_or(""));
            expr.add(MsLang::Unwrap([k]))
        }
        "wrap_array" => {
            let p = sym(expr, op.get_str("path").unwrap_or(""));
            expr.add(MsLang::WrapArray([p]))
        }
        "unwrap_array" => {
            let p = sym(expr, op.get_str("path").unwrap_or(""));
            expr.add(MsLang::UnwrapArray([p]))
        }
        "coerce" => {
            let p = sym(expr, op.get_str("path").unwrap_or(""));
            let t = sym(
                expr,
                op.get_str("targetType")
                    .or_else(|| op.get_str("target_type"))
                    .unwrap_or(""),
            );
            expr.add(MsLang::Coerce([p, t]))
        }
        "default" => {
            let p = sym(expr, op.get_str("path").unwrap_or(""));
            let val = op.fields.get("value").cloned().unwrap_or(Value::Null);
            let v = sym(expr, &serde_json::to_string(&val).unwrap_or_else(|_| "null".into()));
            let on = sym(expr, op.get_str("on").unwrap_or("absent"));
            // Keep full op in registry for lossless roundtrip of complex values.
            let key = format!("default_{:x}", fnv1a64(serde_json::to_string(op).unwrap_or_default().as_bytes()));
            let s = Symbol::from(key.as_str());
            registry.entry(s).or_insert_with(|| op.clone());
            expr.add(MsLang::Default([p, v, on]))
        }
        "remove" => {
            let p = sym(expr, op.get_str("path").unwrap_or(""));
            expr.add(MsLang::Remove([p]))
        }
        _ => {
            let key = format!("raw_{:x}", fnv1a64(serde_json::to_string(op).unwrap_or_default().as_bytes()));
            let s = Symbol::from(key.as_str());
            registry.entry(s).or_insert_with(|| op.clone());
            let leaf = expr.add(MsLang::Symbol(s));
            expr.add(MsLang::Raw([leaf]))
        }
    }
}

fn sym(expr: &mut RecExpr<MsLang>, s: &str) -> Id {
    expr.add(MsLang::Symbol(Symbol::from(s)))
}

fn decode_expr(expr: &RecExpr<MsLang>, registry: &HashMap<Symbol, Op>) -> Option<Vec<Op>> {
    let root = expr.as_ref().last()?;
    decode_node(expr, root, registry)
}

fn decode_node(
    expr: &RecExpr<MsLang>,
    node: &MsLang,
    registry: &HashMap<Symbol, Op>,
) -> Option<Vec<Op>> {
    match node {
        MsLang::Nil => Some(vec![]),
        MsLang::Cons([h, t]) => {
            let head = decode_op_node(expr, &expr[*h], registry)?;
            let mut rest = decode_node(expr, &expr[*t], registry)?;
            let mut out = vec![head];
            out.append(&mut rest);
            Some(out)
        }
        _ => None,
    }
}

fn decode_op_node(
    expr: &RecExpr<MsLang>,
    node: &MsLang,
    registry: &HashMap<Symbol, Op>,
) -> Option<Op> {
    match node {
        MsLang::Rename([a, b]) => Some(op_rename("rename", sym_str(expr, *a)?, sym_str(expr, *b)?)),
        MsLang::Move([a, b]) => Some(op_rename("move", sym_str(expr, *a)?, sym_str(expr, *b)?)),
        MsLang::Wrap([k]) => Some(op1("wrap", "key", sym_str(expr, *k)?)),
        MsLang::Unwrap([k]) => Some(op1("unwrap", "key", sym_str(expr, *k)?)),
        MsLang::WrapArray([p]) => Some(op1("wrap_array", "path", sym_str(expr, *p)?)),
        MsLang::UnwrapArray([p]) => Some(op1("unwrap_array", "path", sym_str(expr, *p)?)),
        MsLang::Coerce([p, t]) => Some(Op {
            op: "coerce".into(),
            fields: serde_json::Map::from_iter([
                ("path".into(), Value::String(sym_str(expr, *p)?)),
                ("targetType".into(), Value::String(sym_str(expr, *t)?)),
            ]),
        }),
        MsLang::Default([p, v, on]) => {
            let path = sym_str(expr, *p)?;
            let on_s = sym_str(expr, *on).unwrap_or_else(|| "absent".into());
            let raw_val = sym_str(expr, *v).unwrap_or_else(|| "null".into());
            let value: Value = serde_json::from_str(&raw_val).unwrap_or(Value::String(raw_val));
            // Prefer registry full op when present (exact roundtrip).
            for op in registry.values() {
                if op.op == "default"
                    && op.get_str("path") == Some(path.as_str())
                    && op.fields.get("value") == Some(&value)
                    && op.get_str("on").unwrap_or("absent") == on_s.as_str()
                {
                    return Some(op.clone());
                }
            }
            Some(Op {
                op: "default".into(),
                fields: serde_json::Map::from_iter([
                    ("path".into(), Value::String(path)),
                    ("value".into(), value),
                    ("on".into(), Value::String(on_s)),
                ]),
            })
        }
        MsLang::Remove([p]) => Some(op1("remove", "path", sym_str(expr, *p)?)),
        MsLang::Raw([s]) => {
            let sym = match &expr[*s] {
                MsLang::Symbol(sy) => *sy,
                _ => return None,
            };
            registry.get(&sym).cloned()
        }
        _ => None,
    }
}

fn sym_str(expr: &RecExpr<MsLang>, id: Id) -> Option<String> {
    match &expr[id] {
        MsLang::Symbol(s) => Some(s.as_str().to_string()),
        _ => None,
    }
}

fn op_rename(kind: &str, from: String, to: String) -> Op {
    Op {
        op: kind.into(),
        fields: serde_json::Map::from_iter([
            ("from".into(), Value::String(from)),
            ("to".into(), Value::String(to)),
        ]),
    }
}

fn op1(kind: &str, key: &str, val: String) -> Op {
    Op {
        op: kind.into(),
        fields: serde_json::Map::from_iter([(key.into(), Value::String(val))]),
    }
}

fn fnv1a64(data: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf29ce484222325;
    for b in data {
        hash ^= u64::from(*b);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

fn commute_disjoint_for_cancel(ops: &[Op]) -> Option<Vec<Op>> {
    let mut ops = ops.to_vec();
    let mut changed = false;
    for i in 0..ops.len().saturating_sub(1) {
        for j in (i + 1)..ops.len() {
            if can_cancel(&ops[i], &ops[j]) {
                let mut ok = true;
                for k in (i + 1)..j {
                    if !paths_disjoint(&ops[i], &ops[k]) || !paths_disjoint(&ops[j], &ops[k]) {
                        ok = false;
                        break;
                    }
                }
                if ok {
                    let moved = ops.remove(j);
                    ops.insert(i + 1, moved);
                    changed = true;
                    break;
                }
            }
        }
    }
    if !changed {
        return None;
    }
    // After commute, wrap/unwrap may be adjacent — egg will cancel; also apply locally.
    Some(ops)
}

fn can_cancel(a: &Op, b: &Op) -> bool {
    (a.op == "wrap" && b.op == "unwrap" && a.get_str("key") == b.get_str("key"))
        || (a.op == "wrap_array"
            && b.op == "unwrap_array"
            && a.get_str("path") == b.get_str("path"))
}

/// True when path arguments do not overlap (for L4 reorder seeds).
pub fn paths_disjoint(a: &Op, b: &Op) -> bool {
    let pa = a.path_args();
    let pb = b.path_args();
    if pa.is_empty() || pb.is_empty() {
        return false;
    }
    for x in &pa {
        for y in &pb {
            if x == y || x.starts_with(&(y.clone() + "/")) || y.starts_with(&(x.clone() + "/")) {
                return false;
            }
        }
    }
    true
}

/// Twin-gated: drop coerce(path, T) when declared schema says path is already T.
pub fn schema_gated_coerce_removal(
    ops: &[Op],
    declared_field_types: &HashMap<String, String>,
) -> Option<Vec<Op>> {
    let mut out = Vec::new();
    let mut changed = false;
    for op in ops {
        if op.op == "coerce" {
            let path = op.get_str("path").unwrap_or("");
            let target = op
                .get_str("targetType")
                .or_else(|| op.get_str("target_type"))
                .unwrap_or("")
                .to_lowercase();
            if let Some(declared) = declared_field_types.get(path) {
                if declared.to_lowercase() == target && !target.is_empty() {
                    changed = true;
                    continue;
                }
            }
        }
        out.push(op.clone());
    }
    changed.then_some(out)
}

/// Twin gates for schema coerce: trust + triggering + non-empty declared types.
pub fn twin_gates_open(
    triggering_gate: bool,
    spec_trust: Option<f64>,
    spec_trust_gate: f64,
    declared_field_types: &HashMap<String, String>,
) -> bool {
    triggering_gate && spec_trust.unwrap_or(0.0) >= spec_trust_gate && !declared_field_types.is_empty()
}

pub fn cost(ops: &[Op]) -> (usize, usize) {
    let mutating = ops.iter().filter(|o| o.value_mutating()).count();
    (ops.len(), mutating)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn rename(from: &str, to: &str) -> Op {
        Op {
            op: "rename".into(),
            fields: serde_json::Map::from_iter([
                ("from".into(), json!(from)),
                ("to".into(), json!(to)),
            ]),
        }
    }

    fn coerce(path: &str, ty: &str) -> Op {
        Op {
            op: "coerce".into(),
            fields: serde_json::Map::from_iter([
                ("path".into(), json!(path)),
                ("targetType".into(), json!(ty)),
            ]),
        }
    }

    #[test]
    fn composes_renames_via_saturation() {
        let ops = vec![rename("/a", "/b"), rename("/b", "/c")];
        let out = saturate_and_extract(&ops, false, &HashMap::new());
        assert_eq!(out.len(), 1, "got {:?}", out);
        assert_eq!(out[0].get_str("from"), Some("/a"));
        assert_eq!(out[0].get_str("to"), Some("/c"));
    }

    #[test]
    fn schema_gated_drops_matching_coerce() {
        let ops = vec![coerce("/amount", "integer"), rename("/a", "/b")];
        let mut types = HashMap::new();
        types.insert("/amount".into(), "integer".into());
        let out = schema_gated_coerce_removal(&ops, &types).unwrap();
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].op, "rename");
    }

    #[test]
    fn schema_gated_keeps_coerce_without_types() {
        let ops = vec![coerce("/amount", "integer")];
        assert!(schema_gated_coerce_removal(&ops, &HashMap::new()).is_none());
    }

    #[test]
    fn twin_gates_block_coerce_without_triggering() {
        let ops = vec![coerce("/amount", "integer"), rename("/a", "/b")];
        let mut types = HashMap::new();
        types.insert("/amount".into(), "integer".into());
        // twin_gates=false → schema seed not applied; coerce remains
        let out = saturate_and_extract(&ops, false, &types);
        assert_eq!(out.len(), 2, "coerce must remain when twin gates closed: {:?}", out);
        assert!(out.iter().any(|o| o.op == "coerce"));
    }

    #[test]
    fn minimize_algebraic_blocks_when_spec_trust_low() {
        let prog = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![coerce("/amount", "integer"), rename("/a", "/b")],
            rationale: None,
            bandit_category: None,
        };
        let mut types = HashMap::new();
        types.insert("/amount".into(), "integer".into());
        let cases = vec![crate::ast::Case {
            input: serde_json::json!({"amount": "1", "a": 1}),
            expected: None,
        }];
        // triggering ok, types ok, but trust below gate
        let (out, improved) = minimize_algebraic(&prog, &cases, Some(0.5), 0.85, true, &types);
        assert!(!improved);
        assert_eq!(out.ops.len(), 2);
        assert!(out.ops.iter().any(|o| o.op == "coerce"));
    }

    #[test]
    fn minimize_algebraic_blocks_when_triggering_missing() {
        let prog = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![coerce("/amount", "integer"), rename("/a", "/b")],
            rationale: None,
            bandit_category: None,
        };
        let mut types = HashMap::new();
        types.insert("/amount".into(), "integer".into());
        let cases = vec![crate::ast::Case {
            input: serde_json::json!({"amount": "1", "a": 1}),
            expected: None,
        }];
        // high trust + types, but no triggering gate
        let (out, improved) = minimize_algebraic(&prog, &cases, Some(0.95), 0.85, false, &types);
        assert!(!improved);
        assert!(out.ops.iter().any(|o| o.op == "coerce"));
    }

    #[test]
    fn minimize_algebraic_drops_when_all_twin_gates_open() {
        let prog = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![coerce("/amount", "integer"), rename("/a", "/b")],
            rationale: None,
            bandit_category: None,
        };
        let mut types = HashMap::new();
        types.insert("/amount".into(), "integer".into());
        // Cases must remain equivalent after dropping coerce: amount already integer-shaped
        let cases = vec![crate::ast::Case {
            input: serde_json::json!({"amount": 1, "a": 1}),
            expected: None,
        }];
        let (out, improved) = minimize_algebraic(&prog, &cases, Some(0.95), 0.85, true, &types);
        assert!(improved, "expected coerce drop: {:?}", out.ops);
        assert_eq!(out.ops.len(), 1);
        assert_eq!(out.ops[0].op, "rename");
    }

    #[test]
    fn twin_gates_open_drops_coerce_via_seed() {
        let ops = vec![coerce("/amount", "integer"), rename("/a", "/b")];
        let mut types = HashMap::new();
        types.insert("/amount".into(), "integer".into());
        assert!(twin_gates_open(true, Some(0.9), 0.85, &types));
        let out = saturate_and_extract(&ops, true, &types);
        assert_eq!(out.len(), 1, "got {:?}", out);
        assert_eq!(out[0].op, "rename");
    }

    #[test]
    fn cancels_wrap_unwrap() {
        let ops = vec![
            Op {
                op: "wrap".into(),
                fields: serde_json::Map::from_iter([("key".into(), json!("data"))]),
            },
            Op {
                op: "unwrap".into(),
                fields: serde_json::Map::from_iter([("key".into(), json!("data"))]),
            },
            rename("/a", "/b"),
        ];
        let out = saturate_and_extract(&ops, false, &HashMap::new());
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].op, "rename");
    }

    #[test]
    fn default_preserves_value_and_on_through_extract() {
        let ops = vec![
            Op {
                op: "default".into(),
                fields: serde_json::Map::from_iter([
                    ("path".into(), json!("/x")),
                    ("value".into(), json!({"n": 1})),
                    ("on".into(), json!("missing")),
                ]),
            },
            rename("/a", "/b"),
        ];
        let out = saturate_and_extract(&ops, false, &HashMap::new());
        let d = out.iter().find(|o| o.op == "default").expect("default kept");
        assert_eq!(d.get_str("path"), Some("/x"));
        assert_eq!(d.get_str("on"), Some("missing"));
        assert_eq!(d.fields.get("value"), Some(&json!({"n": 1})));
    }

    #[test]
    fn path_args_includes_wrap_key_for_commute() {
        let wrap = Op {
            op: "wrap".into(),
            fields: serde_json::Map::from_iter([("key".into(), json!("data"))]),
        };
        let rename_op = rename("/a", "/b");
        let unwrap = Op {
            op: "unwrap".into(),
            fields: serde_json::Map::from_iter([("key".into(), json!("data"))]),
        };
        assert!(paths_disjoint(&wrap, &rename_op));
        let ops = vec![wrap, rename_op, unwrap];
        let seeds = context_seeds(&ops, false, &HashMap::new());
        assert!(!seeds.is_empty(), "commute seed should fire for wrap/rename/unwrap");
        let cancelled = saturate_and_extract(&ops, false, &HashMap::new());
        assert_eq!(cancelled.len(), 1);
        assert_eq!(cancelled[0].op, "rename");
    }
}
