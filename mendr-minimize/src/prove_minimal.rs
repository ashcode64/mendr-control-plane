//! L4 — Bounded SyGuS-lite + CEGIS over a restricted grammar.
//!
//! Grammar: subsequences of the draft **and** of adjacent disjoint-path reorderings,
//! filtered by optional `allowedOpcodes`. Size-gated at `max_ops` (hard cap 8).

use crate::ast::{Case, Op, Program};
use crate::eqsat::{cost, paths_disjoint};
use crate::interpret::{counterexamples, programs_equivalent};

const HARD_CAP: usize = 8;

pub fn prove_minimal(
    program: &Program,
    cases: &[Case],
    max_ops: usize,
    allowed_opcodes: Option<&[String]>,
) -> (Program, bool) {
    let k = program.op_count();
    let gate = max_ops.min(HARD_CAP);
    if k == 0 || k > gate || cases.is_empty() {
        return (program.clone(), false);
    }

    let allowed: Option<std::collections::HashSet<String>> = allowed_opcodes.map(|a| {
        a.iter()
            .map(|s| s.to_lowercase())
            .collect()
    });

    let ops: Vec<Op> = program
        .ops
        .iter()
        .filter(|o| {
            allowed
                .as_ref()
                .map(|set| set.contains(&o.op.to_lowercase()))
                .unwrap_or(true)
        })
        .cloned()
        .collect();
    let base = if ops.len() == program.ops.len() {
        program.clone()
    } else {
        let filtered = program.with_ops(ops.clone());
        if programs_equivalent(program, &filtered, cases) {
            filtered
        } else {
            program.clone()
        }
    };
    let ops = base.ops.clone();
    let k = ops.len();
    if k == 0 || k > gate {
        return (program.clone(), false);
    }

    let seeds = reorder_seeds(&ops);
    let mut active_cases: Vec<Case> = cases.to_vec();
    let mut best: Option<Program> = None;
    let draft_cost = cost(&program.ops);

    for _round in 0..4 {
        let mut found: Option<Vec<Op>> = None;
        'search: for size in 0..k {
            for seed in &seeds {
                if let Some(subset) = first_equivalent_subsequence(seed, size, &base, &active_cases)
                {
                    found = Some(subset);
                    break 'search;
                }
            }
        }
        let Some(subset) = found else {
            break;
        };
        let cand = base.with_ops(subset);
        if !programs_equivalent(program, &cand, cases) {
            let ces = counterexamples(program, &cand, cases);
            let before = active_cases.len();
            for ce in ces {
                if !active_cases.iter().any(|c| c.input == ce) {
                    active_cases.push(Case {
                        input: ce,
                        expected: None,
                    });
                }
            }
            if active_cases.len() == before {
                break;
            }
            continue;
        }
        let c = cost(&cand.ops);
        if c < draft_cost {
            best = Some(cand);
        }
        break;
    }

    match best {
        Some(p) if cost(&p.ops) < draft_cost => (p, true),
        _ => (program.clone(), false),
    }
}

/// Original order plus sequences from adjacent swaps of path-disjoint ops.
fn reorder_seeds(ops: &[Op]) -> Vec<Vec<Op>> {
    let mut seeds = vec![ops.to_vec()];
    let mut seen = std::collections::HashSet::new();
    seen.insert(fingerprint(ops));
    // Bounded bubble: repeatedly apply one adjacent disjoint swap.
    let mut frontier = vec![ops.to_vec()];
    let mut steps = 0usize;
    while let Some(cur) = frontier.pop() {
        steps += 1;
        if steps > 64 || seeds.len() > 32 {
            break;
        }
        for i in 0..cur.len().saturating_sub(1) {
            if !paths_disjoint(&cur[i], &cur[i + 1]) {
                continue;
            }
            let mut next = cur.clone();
            next.swap(i, i + 1);
            let fp = fingerprint(&next);
            if seen.insert(fp) {
                seeds.push(next.clone());
                frontier.push(next);
            }
        }
    }
    seeds
}

fn fingerprint(ops: &[Op]) -> String {
    serde_json::to_string(ops).unwrap_or_default()
}

fn first_equivalent_subsequence(
    ops: &[Op],
    size: usize,
    draft: &Program,
    cases: &[Case],
) -> Option<Vec<Op>> {
    let n = ops.len();
    if size > n {
        return None;
    }
    if size == 0 {
        let cand = draft.with_ops(vec![]);
        return if programs_equivalent(draft, &cand, cases) {
            Some(vec![])
        } else {
            None
        };
    }
    let mut cur = Vec::new();
    choose(n, size, 0, &mut cur, &mut |chosen| {
        let subset: Vec<Op> = chosen.iter().map(|&i| ops[i].clone()).collect();
        let cand = draft.with_ops(subset.clone());
        if programs_equivalent(draft, &cand, cases) {
            Some(subset)
        } else {
            None
        }
    })
}

fn choose<R>(
    n: usize,
    k: usize,
    start: usize,
    cur: &mut Vec<usize>,
    f: &mut dyn FnMut(&[usize]) -> Option<R>,
) -> Option<R> {
    if cur.len() == k {
        return f(cur);
    }
    for i in start..n {
        cur.push(i);
        if let Some(r) = choose(n, k, i + 1, cur, f) {
            return Some(r);
        }
        cur.pop();
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn reorder_seeds_swaps_disjoint() {
        let a = Op {
            op: "remove".into(),
            fields: serde_json::Map::from_iter([("path".into(), json!("/a"))]),
        };
        let b = Op {
            op: "remove".into(),
            fields: serde_json::Map::from_iter([("path".into(), json!("/b"))]),
        };
        let seeds = reorder_seeds(&[a, b]);
        assert!(seeds.len() >= 2);
    }
}
