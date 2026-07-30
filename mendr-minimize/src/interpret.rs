//! JSON-pointer transform interpreter for search probes.
//! Semantics intentionally mirror Java MendrScriptExecutor / JsonPointers.
//! Final correctness is always re-checked by Java.

use crate::ast::{Op, Program};
use serde_json::{Map, Number, Value};

pub fn apply_program(program: &Program, input: &Value) -> Result<Value, String> {
    let mut cur = input.clone();
    for op in &program.ops {
        cur = apply_op(op, cur)?;
    }
    Ok(cur)
}

fn apply_op(op: &Op, root: Value) -> Result<Value, String> {
    match op.op.as_str() {
        "rename" | "move" => {
            let from = op.get_str("from").ok_or("rename/move missing from")?;
            let to = op.get_str("to").ok_or("rename/move missing to")?;
            move_value(root, from, to, true)
        }
        "copy" => {
            let from = op.get_str("from").ok_or("copy missing from")?;
            let to = op.get_str("to").ok_or("copy missing to")?;
            match get_path(&root, from).cloned() {
                Some(v) => Ok(set_path(root, to, v)?),
                None => Ok(root),
            }
        }
        "remove" => {
            let path = op.get_str("path").ok_or("remove missing path")?;
            Ok(delete_path(root, path))
        }
        "wrap" => {
            let key = op.get_str("key").ok_or("wrap missing key")?;
            Ok(Value::Object(Map::from_iter([(key.to_string(), root)])))
        }
        "unwrap" => {
            let key = op.get_str("key").ok_or("unwrap missing key")?;
            match root {
                Value::Object(mut m) => Ok(m.remove(key).unwrap_or(Value::Null)),
                other => Ok(other),
            }
        }
        "wrap_array" => {
            let path = op.get_str("path").ok_or("wrap_array missing path")?;
            let v = get_path(&root, path).cloned().unwrap_or(Value::Null);
            Ok(set_path(root, path, Value::Array(vec![v]))?)
        }
        "unwrap_array" => {
            let path = op.get_str("path").ok_or("unwrap_array missing path")?;
            match get_path(&root, path).cloned() {
                Some(Value::Array(mut a)) if !a.is_empty() => {
                    Ok(set_path(root, path, a.remove(0))?)
                }
                _ => Ok(root),
            }
        }
        "default" => {
            let path = op.get_str("path").ok_or("default missing path")?;
            let value = op.fields.get("value").cloned().unwrap_or(Value::Null);
            let on = op.get_str("on").unwrap_or("absent").to_lowercase();
            let cur = get_path(&root, path);
            let fire = match on.as_str() {
                "null" => cur.map(|v| v.is_null()).unwrap_or(false),
                "both" => cur.map(|v| v.is_null()).unwrap_or(true),
                "missing" | "absent" => cur.is_none(),
                _ => cur.is_none(),
            };
            if fire {
                Ok(set_path(root, path, value)?)
            } else {
                Ok(root)
            }
        }
        // Java: coalesce fires only when path EXISTS and is JSON null (not when absent).
        "coalesce" => {
            let path = op.get_str("path").ok_or("coalesce missing path")?;
            let value = op.fields.get("value").cloned().unwrap_or(Value::Null);
            match get_path(&root, path) {
                Some(Value::Null) => Ok(set_path(root, path, value)?),
                _ => Ok(root),
            }
        }
        "coerce" => {
            let path = op.get_str("path").ok_or("coerce missing path")?;
            let target = op
                .get_str("targetType")
                .or_else(|| op.get_str("target_type"))
                .unwrap_or("string");
            match get_path(&root, path).cloned() {
                Some(v) => Ok(set_path(root, path, coerce_value(v, target)?)?),
                None => Ok(root),
            }
        }
        "scale" => apply_scale(op, root),
        "arith" => apply_arith(op, root),
        "map_value" => apply_map_value(op, root),
        "string" => apply_string(op, root),
        "reformat_date" => {
            // Apply as identity for present paths so multi-op programs can still be
            // compared for *other* ops. Dropping reformat_date is blocked in
            // matches_draft / programs_equivalent via opaque-op checks.
            let path = op.get_str("path").ok_or("reformat_date missing path")?;
            if get_path(&root, path).is_none() {
                return Ok(root);
            }
            Ok(root)
        }
        "conditional" => apply_conditional(op, root),
        "strip_unknown" => {
            let path = op.get_str("path").unwrap_or("");
            let allowed: Vec<String> = op
                .fields
                .get("allowed")
                .and_then(|a| a.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|x| x.as_str().map(|s| s.to_string()))
                        .collect()
                })
                .unwrap_or_default();
            if path.is_empty() || path == "/" {
                if let Value::Object(m) = root {
                    let filtered: Map<String, Value> = m
                        .into_iter()
                        .filter(|(k, _)| allowed.iter().any(|a| a == k))
                        .collect();
                    return Ok(Value::Object(filtered));
                }
                return Ok(root);
            }
            match get_path(&root, path).cloned() {
                Some(Value::Object(m)) => {
                    let filtered: Map<String, Value> = m
                        .into_iter()
                        .filter(|(k, _)| allowed.iter().any(|a| a == k))
                        .collect();
                    Ok(set_path(root, path, Value::Object(filtered))?)
                }
                _ => Ok(root),
            }
        }
        other => Err(format!("unknown opcode: {other}")),
    }
}

fn apply_scale(op: &Op, root: Value) -> Result<Value, String> {
    let path = op.get_str("path").ok_or("scale missing path")?;
    if get_path(&root, path).is_none() {
        return Ok(root);
    }
    let num = op
        .fields
        .get("numerator")
        .and_then(|v| v.as_f64())
        .unwrap_or(1.0);
    let den = op
        .fields
        .get("denominator")
        .and_then(|v| v.as_f64())
        .unwrap_or(1.0);
    if den == 0.0 {
        return Err("scale denominator is zero".into());
    }
    let v = to_number(get_path(&root, path).unwrap())?;
    let result = v * num / den;
    check_bounds(op, result)?;
    Ok(set_path(root, path, json_number(result))?)
}

fn apply_arith(op: &Op, root: Value) -> Result<Value, String> {
    let path = op.get_str("path").ok_or("arith missing path")?;
    if get_path(&root, path).is_none() {
        return Ok(root);
    }
    let operator = op.get_str("operator").unwrap_or("+");
    let operand = op
        .fields
        .get("operand")
        .and_then(|v| v.as_f64())
        .unwrap_or(0.0);
    let v = to_number(get_path(&root, path).unwrap())?;
    let result = match operator {
        "+" => v + operand,
        "-" => v - operand,
        "*" => v * operand,
        "/" => {
            if operand == 0.0 {
                return Err("arith divide by zero".into());
            }
            v / operand
        }
        other => return Err(format!("unknown arith operator: {other}")),
    };
    check_bounds(op, result)?;
    Ok(set_path(root, path, json_number(result))?)
}

fn apply_map_value(op: &Op, root: Value) -> Result<Value, String> {
    let path = op.get_str("path").ok_or("map_value missing path")?;
    let Some(cur) = get_path(&root, path).cloned() else {
        return Ok(root);
    };
    let key = match &cur {
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Null => "null".into(),
        _ => return Ok(root),
    };
    let mapping = op.fields.get("mapping").and_then(|m| m.as_object());
    if let Some(map) = mapping {
        if let Some(v) = map.get(&key) {
            return Ok(set_path(root, path, v.clone())?);
        }
    }
    let on = op.get_str("onUnmapped").or_else(|| op.get_str("on_unmapped")).unwrap_or("passthrough");
    match on {
        "reject" => Err(format!("map_value unmapped key: {key}")),
        "quarantine" => Ok(set_path(root, path, Value::Null)?),
        _ => Ok(root),
    }
}

fn apply_string(op: &Op, root: Value) -> Result<Value, String> {
    let path = op.get_str("path").ok_or("string missing path")?;
    let Some(Value::String(s)) = get_path(&root, path).cloned() else {
        return Ok(root);
    };
    let operation = op.get_str("operation").unwrap_or("trim");
    let args = op.fields.get("args").cloned().unwrap_or(Value::Array(vec![]));
    let out = match operation {
        "trim" => s.trim().to_string(),
        "lower" | "lowercase" => s.to_lowercase(),
        "upper" | "uppercase" => s.to_uppercase(),
        "replace" => {
            let arr = args.as_array();
            let from = arr.and_then(|a| a.first()).and_then(|v| v.as_str()).unwrap_or("");
            let to = arr.and_then(|a| a.get(1)).and_then(|v| v.as_str()).unwrap_or("");
            s.replace(from, to)
        }
        _ => s,
    };
    Ok(set_path(root, path, Value::String(out))?)
}

fn apply_conditional(op: &Op, root: Value) -> Result<Value, String> {
    let pred = op.fields.get("predicate").ok_or("conditional missing predicate")?;
    let then_ops = op_list(op.fields.get("then"));
    let else_ops = op_list(op.fields.get("otherwise").or_else(|| op.fields.get("else")));
    let branch = if eval_predicate(pred, &root)? {
        then_ops
    } else {
        else_ops
    };
    let mut cur = root;
    for child in branch {
        cur = apply_op(&child, cur)?;
    }
    Ok(cur)
}

fn op_list(v: Option<&Value>) -> Vec<Op> {
    v.and_then(|x| x.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|item| serde_json::from_value::<Op>(item.clone()).ok())
                .collect()
        })
        .unwrap_or_default()
}

fn eval_predicate(pred: &Value, root: &Value) -> Result<bool, String> {
    let obj = pred.as_object().ok_or("predicate must be object")?;
    if let Some(args) = obj.get("eq").and_then(|v| v.as_array()) {
        if args.len() >= 2 {
            let left = resolve_pred_arg(&args[0], root);
            let right = resolve_pred_arg(&args[1], root);
            return Ok(left == right);
        }
    }
    if let Some(path) = obj.get("exists").and_then(|v| v.as_str()) {
        return Ok(get_path(root, path).is_some());
    }
    // Unknown predicate → unresolved-ish: treat as false (safe, won't take then-branch blindly)
    Ok(false)
}

fn resolve_pred_arg(v: &Value, root: &Value) -> Value {
    if let Some(s) = v.as_str() {
        if s.starts_with('/') {
            return get_path(root, s).cloned().unwrap_or(Value::Null);
        }
        return Value::String(s.into());
    }
    v.clone()
}

fn coerce_value(v: Value, target: &str) -> Result<Value, String> {
    match target.to_lowercase().as_str() {
        "string" => Ok(Value::String(match v {
            Value::String(s) => s,
            Value::Number(n) => n.to_string(),
            Value::Bool(b) => b.to_string(),
            Value::Null => "null".into(),
            other => other.to_string(),
        })),
        "integer" | "int" | "long" => {
            let n = to_number(&v)?;
            Ok(Value::Number(Number::from(n.round() as i64)))
        }
        "number" | "double" | "float" => Ok(json_number(to_number(&v)?)),
        "boolean" | "bool" => Ok(Value::Bool(to_bool(&v))),
        other => Err(format!("unknown coerce target: {other}")),
    }
}

fn to_number(v: &Value) -> Result<f64, String> {
    match v {
        Value::Number(n) => n.as_f64().ok_or_else(|| "non-finite number".into()),
        Value::String(s) => s.parse::<f64>().map_err(|_| format!("not a number: {s}")),
        Value::Bool(b) => Ok(if *b { 1.0 } else { 0.0 }),
        _ => Err("value is not numeric".into()),
    }
}

fn to_bool(v: &Value) -> bool {
    match v {
        Value::Bool(b) => *b,
        Value::String(s) => matches!(s.to_lowercase().as_str(), "true" | "1" | "yes"),
        Value::Number(n) => n.as_f64().map(|x| x != 0.0).unwrap_or(false),
        Value::Null => false,
        _ => true,
    }
}

fn json_number(v: f64) -> Value {
    if v.fract() == 0.0 && v.abs() < (i64::MAX as f64) {
        Value::Number(Number::from(v as i64))
    } else {
        Number::from_f64(v)
            .map(Value::Number)
            .unwrap_or(Value::Null)
    }
}

fn check_bounds(op: &Op, result: f64) -> Result<(), String> {
    if let Some(min) = op.fields.get("expectedMin").and_then(|v| v.as_f64()) {
        if result < min {
            return Err(format!("result {result} < expectedMin {min}"));
        }
    }
    if let Some(max) = op.fields.get("expectedMax").and_then(|v| v.as_f64()) {
        if result > max {
            return Err(format!("result {result} > expectedMax {max}"));
        }
    }
    Ok(())
}

fn move_value(root: Value, from: &str, to: &str, delete_src: bool) -> Result<Value, String> {
    let v = match get_path(&root, from).cloned() {
        Some(v) => v,
        None => return Ok(root),
    };
    let mut root = set_path(root, to, v)?;
    if delete_src && from != to {
        root = delete_path(root, from);
    }
    Ok(root)
}

pub fn get_path<'a>(root: &'a Value, pointer: &str) -> Option<&'a Value> {
    if pointer.is_empty() || pointer == "/" {
        return Some(root);
    }
    let mut cur = root;
    for seg in pointer.trim_start_matches('/').split('/') {
        let seg = seg.replace("~1", "/").replace("~0", "~");
        match cur {
            Value::Object(m) => cur = m.get(&seg)?,
            Value::Array(a) => {
                let i: usize = seg.parse().ok()?;
                cur = a.get(i)?;
            }
            _ => return None,
        }
    }
    Some(cur)
}

pub fn set_path(root: Value, pointer: &str, value: Value) -> Result<Value, String> {
    if pointer.is_empty() || pointer == "/" {
        return Ok(value);
    }
    let segs: Vec<String> = pointer
        .trim_start_matches('/')
        .split('/')
        .map(|s| s.replace("~1", "/").replace("~0", "~"))
        .collect();
    set_segs(root, &segs, value)
}

fn set_segs(root: Value, segs: &[String], value: Value) -> Result<Value, String> {
    if segs.is_empty() {
        return Ok(value);
    }
    let head = &segs[0];
    let rest = &segs[1..];
    match root {
        Value::Object(mut m) => {
            let child = m.remove(head).unwrap_or_else(|| {
                // Prefer object intermediates; if next seg looks like array index, use array.
                if !rest.is_empty() && rest[0].parse::<usize>().is_ok() {
                    Value::Array(vec![])
                } else {
                    Value::Object(Map::new())
                }
            });
            m.insert(head.clone(), set_segs(child, rest, value)?);
            Ok(Value::Object(m))
        }
        Value::Array(mut a) => {
            let idx: usize = head
                .parse()
                .map_err(|_| format!("array index not a number: {head}"))?;
            if idx >= a.len() {
                return Err(format!("array index out of bounds: {idx}"));
            }
            if rest.is_empty() {
                a[idx] = value;
            } else {
                a[idx] = set_segs(a[idx].clone(), rest, value)?;
            }
            Ok(Value::Array(a))
        }
        Value::Null => {
            let child = if !rest.is_empty() && rest[0].parse::<usize>().is_ok() {
                Value::Array(vec![])
            } else {
                Value::Object(Map::new())
            };
            let mut m = Map::new();
            m.insert(head.clone(), set_segs(child, rest, value)?);
            Ok(Value::Object(m))
        }
        _ => Err("cannot set path on scalar parent".into()),
    }
}

pub fn delete_path(root: Value, pointer: &str) -> Value {
    if pointer.is_empty() || pointer == "/" {
        return Value::Null;
    }
    let segs: Vec<String> = pointer
        .trim_start_matches('/')
        .split('/')
        .map(|s| s.replace("~1", "/").replace("~0", "~"))
        .collect();
    delete_segs(root.clone(), &segs).unwrap_or(root)
}

fn delete_segs(root: Value, segs: &[String]) -> Result<Value, String> {
    if segs.is_empty() {
        return Ok(Value::Null);
    }
    let head = &segs[0];
    if segs.len() == 1 {
        return match root {
            Value::Object(mut m) => {
                m.remove(head);
                Ok(Value::Object(m))
            }
            Value::Array(mut a) => {
                if let Ok(idx) = head.parse::<usize>() {
                    if idx < a.len() {
                        a.remove(idx);
                    }
                }
                Ok(Value::Array(a))
            }
            other => Ok(other),
        };
    }
    match root {
        Value::Object(mut m) => {
            if let Some(child) = m.remove(head) {
                m.insert(head.clone(), delete_segs(child, &segs[1..])?);
            }
            Ok(Value::Object(m))
        }
        Value::Array(mut a) => {
            let idx: usize = head.parse().map_err(|_| "bad array index")?;
            if idx < a.len() {
                a[idx] = delete_segs(a[idx].clone(), &segs[1..])?;
            }
            Ok(Value::Array(a))
        }
        other => Ok(other),
    }
}

/// Equivalence oracle for probes: same outputs on all case inputs (and optional expected).
pub fn programs_equivalent(a: &Program, b: &Program, cases: &[crate::ast::Case]) -> bool {
    // Fail-closed: cannot drop reformat_date via identity probe.
    if dropped_opaque_probe_ops(a, b) || dropped_opaque_probe_ops(b, a) {
        return false;
    }
    if cases.is_empty() {
        return a.ops == b.ops;
    }
    for case in cases {
        let oa = match apply_program(a, &case.input) {
            Ok(v) => v,
            Err(_) => return false,
        };
        let ob = match apply_program(b, &case.input) {
            Ok(v) => v,
            Err(_) => return false,
        };
        if oa != ob {
            return false;
        }
        if let Some(exp) = &case.expected {
            if &oa != exp {
                return false;
            }
        }
    }
    true
}

/// Collect inputs where `a` and `b` diverge (CEGIS counterexamples).
pub fn counterexamples(a: &Program, b: &Program, cases: &[crate::ast::Case]) -> Vec<Value> {
    let mut out = Vec::new();
    for case in cases {
        let oa = apply_program(a, &case.input);
        let ob = apply_program(b, &case.input);
        match (oa, ob) {
            (Ok(x), Ok(y)) if x == y => {}
            _ => out.push(case.input.clone()),
        }
    }
    out
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Pass,
    Fail,
    Unresolved,
}

/// Path is uninspectable when it embeds OpenAPI oneOf/anyOf (Java DdminOracle parity)
/// or matches an explicit hint (exact or prefix).
pub fn is_unresolvable_path(path: &str, hints: &[String]) -> bool {
    let lower = path.to_lowercase();
    if lower.contains("oneof") || lower.contains("anyof") {
        return true;
    }
    hints.iter().any(|h| {
        path == h.as_str()
            || path.starts_with(&(h.clone() + "/"))
            || h.starts_with(&(path.to_string() + "/"))
    })
}

fn op_paths_unresolvable(op: &Op, hints: &[String]) -> bool {
    op.path_args()
        .iter()
        .any(|p| is_unresolvable_path(p, hints))
}

/// Ops present in `draft` but missing from `candidate` that touch unresolvable paths.
fn dropped_unresolvable_ops(draft: &Program, candidate: &Program, hints: &[String]) -> bool {
    let cand_fps: std::collections::HashSet<String> = candidate
        .ops
        .iter()
        .map(|o| serde_json::to_string(o).unwrap_or_default())
        .collect();
    draft.ops.iter().any(|o| {
        op_paths_unresolvable(o, hints)
            && !cand_fps.contains(&serde_json::to_string(o).unwrap_or_default())
    })
}

/// Fail-closed: never drop reformat_date via probe equivalence (no format parity).
fn dropped_opaque_probe_ops(draft: &Program, candidate: &Program) -> bool {
    let cand_fps: std::collections::HashSet<String> = candidate
        .ops
        .iter()
        .map(|o| serde_json::to_string(o).unwrap_or_default())
        .collect();
    draft.ops.iter().any(|o| {
        o.op == "reformat_date"
            && !cand_fps.contains(&serde_json::to_string(o).unwrap_or_default())
    })
}

pub fn matches_draft(
    draft: &Program,
    candidate: &Program,
    cases: &[crate::ast::Case],
    unresolvable_paths: &[String],
) -> Outcome {
    // Never coerce: dropping ops that touch oneOf/anyOf / hinted paths → Unresolved.
    if dropped_unresolvable_ops(draft, candidate, unresolvable_paths) {
        return Outcome::Unresolved;
    }
    if dropped_opaque_probe_ops(draft, candidate) {
        return Outcome::Unresolved;
    }
    if cases.is_empty() {
        return if candidate.ops.len() <= draft.ops.len() {
            Outcome::Pass
        } else {
            Outcome::Fail
        };
    }
    for case in cases {
        let want = match apply_program(draft, &case.input) {
            Ok(v) => v,
            Err(_) => return Outcome::Unresolved,
        };
        let got = match apply_program(candidate, &case.input) {
            Ok(v) => v,
            Err(_) => return Outcome::Unresolved,
        };
        if got != want {
            return Outcome::Fail;
        }
        if let Some(exp) = &case.expected {
            if &got != exp {
                return Outcome::Fail;
            }
        }
    }
    Outcome::Pass
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn coalesce_only_when_present_null() {
        let op = Op {
            op: "coalesce".into(),
            fields: serde_json::Map::from_iter([
                ("path".into(), json!("/x")),
                ("value".into(), json!(1)),
            ]),
        };
        let prog = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![op],
            rationale: None,
            bandit_category: None,
        };
        // absent → no change (Java parity)
        let out = apply_program(&prog, &json!({})).unwrap();
        assert_eq!(out, json!({}));
        // present null → set
        let out = apply_program(&prog, &json!({"x": null})).unwrap();
        assert_eq!(out, json!({"x": 1}));
    }

    #[test]
    fn set_path_array_index() {
        let root = json!({"items": [{"a": 1}, {"a": 2}]});
        let out = set_path(root, "/items/1/a", json!(9)).unwrap();
        assert_eq!(out, json!({"items": [{"a": 1}, {"a": 9}]}));
    }

    #[test]
    fn scale_mutates() {
        let op = Op {
            op: "scale".into(),
            fields: serde_json::Map::from_iter([
                ("path".into(), json!("/n")),
                ("numerator".into(), json!(1)),
                ("denominator".into(), json!(100)),
            ]),
        };
        let prog = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![op],
            rationale: None,
            bandit_category: None,
        };
        let out = apply_program(&prog, &json!({"n": 250})).unwrap();
        assert_eq!(out["n"].as_f64().unwrap(), 2.5);
    }

    #[test]
    fn reformat_date_drop_is_unresolved_or_not_equivalent() {
        let with_fmt = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![
                Op {
                    op: "reformat_date".into(),
                    fields: serde_json::Map::from_iter([
                        ("path".into(), json!("/d")),
                        ("fromFormat".into(), json!("yyyy-MM-dd")),
                        ("toFormat".into(), json!("dd/MM/yyyy")),
                    ]),
                },
                Op {
                    op: "rename".into(),
                    fields: serde_json::Map::from_iter([
                        ("from".into(), json!("/a")),
                        ("to".into(), json!("/b")),
                    ]),
                },
            ],
            rationale: None,
            bandit_category: None,
        };
        let without_fmt = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![Op {
                op: "rename".into(),
                fields: serde_json::Map::from_iter([
                    ("from".into(), json!("/a")),
                    ("to".into(), json!("/b")),
                ]),
            }],
            rationale: None,
            bandit_category: None,
        };
        let cases = vec![crate::ast::Case {
            input: json!({"d": "2024-01-01", "a": 1}),
            expected: None,
        }];
        assert_eq!(
            matches_draft(&with_fmt, &without_fmt, &cases, &[]),
            Outcome::Unresolved,
            "dropping reformat_date must be Unresolved for necessity"
        );
        assert!(
            !programs_equivalent(&with_fmt, &without_fmt, &cases),
            "dropping reformat_date must not count as equivalent for L3/L4"
        );
        // Same opaque op present in both → still comparable for other ops
        assert!(programs_equivalent(&with_fmt, &with_fmt, &cases));
    }

    #[test]
    fn parity_fixtures_match_expected() {
        let raw = include_str!("../fixtures/parity_cases.json");
        let fixtures: Vec<serde_json::Value> = serde_json::from_str(raw).unwrap();
        for fix in fixtures {
            let name = fix["name"].as_str().unwrap_or("?");
            let prog: Program = serde_json::from_value(fix["program"].clone()).unwrap();
            for (i, case) in fix["cases"].as_array().unwrap().iter().enumerate() {
                let input = &case["input"];
                let expected = &case["expected"];
                let out = apply_program(&prog, input).unwrap_or_else(|e| panic!("{name}[{i}]: {e}"));
                assert_eq!(&out, expected, "fixture {name} case {i}");
            }
        }
    }
}