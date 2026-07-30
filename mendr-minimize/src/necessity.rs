//! L2 — 1-minimal necessary op subsequence (delta-debugging style reduction).
//!
//! Ternary oracle: Pass / Fail / Unresolved. Unresolved (oneOf/anyOf / uninspectable
//! paths, or interpreter errors) is never coerced to Pass — matching Java DdminOracleService.

use crate::ast::{Case, Op, Program};
use crate::interpret::{is_unresolvable_path, matches_draft, Outcome};

/// Drop ops that are not required for equivalence with the draft on `cases`.
pub fn prune_necessity(
    draft: &Program,
    cases: &[Case],
    unresolvable_paths: &[String],
) -> (Program, bool) {
    let ops = draft.ops.clone();
    if ops.is_empty() {
        return (draft.clone(), false);
    }
    if cases.is_empty() {
        return (draft.clone(), false);
    }

    let idxs: Vec<usize> = (0..ops.len()).collect();
    let minimal_idxs = ddmin_idxs(&idxs, |subset| {
        let sub_ops: Vec<Op> = subset.iter().map(|&i| ops[i].clone()).collect();
        let cand = draft.with_ops(sub_ops);
        match matches_draft(draft, &cand, cases, unresolvable_paths) {
            Outcome::Pass => Outcome::Fail, // still equivalent → shrink
            Outcome::Fail => Outcome::Pass,
            Outcome::Unresolved => Outcome::Unresolved,
        }
    });

    let mut minimal_idxs = minimal_idxs;
    let mut changed = true;
    while changed {
        changed = false;
        for i in 0..minimal_idxs.len() {
            let mut trial = minimal_idxs.clone();
            trial.remove(i);
            let sub_ops: Vec<Op> = trial.iter().map(|&j| ops[j].clone()).collect();
            let cand = draft.with_ops(sub_ops);
            match matches_draft(draft, &cand, cases, unresolvable_paths) {
                Outcome::Pass => {
                    minimal_idxs = trial;
                    changed = true;
                    break;
                }
                Outcome::Unresolved | Outcome::Fail => {}
            }
        }
    }

    let minimal_ops: Vec<Op> = minimal_idxs.iter().map(|&i| ops[i].clone()).collect();
    let cand = draft.with_ops(minimal_ops);
    if matches_draft(draft, &cand, cases, unresolvable_paths) != Outcome::Pass {
        return (draft.clone(), false);
    }
    let shrunk = cand.op_count() < draft.op_count();
    (cand, shrunk)
}

fn ddmin_idxs(circ: &[usize], test: impl Fn(&[usize]) -> Outcome) -> Vec<usize> {
    if circ.is_empty() {
        return vec![];
    }
    if test(&[]) == Outcome::Fail {
        return vec![];
    }
    ddmin_rec(circ.to_vec(), 2, &test)
}

fn ddmin_rec(circ: Vec<usize>, n: usize, test: &impl Fn(&[usize]) -> Outcome) -> Vec<usize> {
    if circ.len() <= 1 {
        return circ;
    }
    let subsets = split(&circ, n);
    for subset in &subsets {
        match test(subset) {
            Outcome::Fail => return ddmin_rec(subset.clone(), 2, test),
            Outcome::Unresolved => continue,
            Outcome::Pass => {}
        }
    }
    for subset in &subsets {
        let comp: Vec<usize> = circ
            .iter()
            .copied()
            .filter(|x| !subset.contains(x))
            .collect();
        if comp.is_empty() {
            continue;
        }
        match test(&comp) {
            Outcome::Fail => return ddmin_rec(comp, n.saturating_sub(1).max(2), test),
            Outcome::Unresolved | Outcome::Pass => {}
        }
    }
    if n < circ.len() {
        return ddmin_rec(circ.clone(), (2 * n).min(circ.len()), test);
    }
    circ
}

fn split(circ: &[usize], n: usize) -> Vec<Vec<usize>> {
    let n = n.max(1).min(circ.len());
    let mut out = Vec::with_capacity(n);
    let size = circ.len() / n;
    let mut start = 0;
    for i in 0..n {
        let extra = if i < circ.len() % n { 1 } else { 0 };
        let end = (start + size + extra).min(circ.len());
        out.push(circ[start..end].to_vec());
        start = end;
    }
    out
}

/// True when an op touches an uninspectable path.
#[allow(dead_code)]
pub fn op_touches_unresolvable(op: &Op, hints: &[String]) -> bool {
    op.path_args()
        .iter()
        .any(|p| is_unresolvable_path(p, hints))
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

    #[test]
    fn oneof_path_not_dropped_by_necessity() {
        let draft = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![
                rename("/payload/oneOf/0/amount", "/amount"),
                rename("/a", "/b"),
            ],
            rationale: None,
            bandit_category: None,
        };
        let cases = vec![Case {
            input: json!({"payload": {"oneOf": [{"amount": 1}]}, "a": 2}),
            expected: None,
        }];
        let (out, _) = prune_necessity(&draft, &cases, &[]);
        assert!(
            out.ops
                .iter()
                .any(|o| o.get_str("from") == Some("/payload/oneOf/0/amount")),
            "oneOf op must remain: {:?}",
            out.ops
        );
    }

    #[test]
    fn explicit_unresolvable_hint_blocks_drop() {
        let draft = Program {
            schema_version: "mendrscript/v1".into(),
            ops: vec![rename("/poly/x", "/x"), rename("/a", "/b")],
            rationale: None,
            bandit_category: None,
        };
        let cases = vec![Case {
            input: json!({"poly": {"x": 1}, "a": 2}),
            expected: None,
        }];
        let hints = vec!["/poly/x".into()];
        let (out, _) = prune_necessity(&draft, &cases, &hints);
        assert!(
            out.ops.iter().any(|o| o.get_str("from") == Some("/poly/x")),
            "hinted unresolvable op must remain: {:?}",
            out.ops
        );
    }
}
