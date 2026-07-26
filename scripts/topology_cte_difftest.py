#!/usr/bin/env python3
"""Differential test for the service-topology recursive CTEs.

The blast-radius / root-cause / cycle traversals in
`ai-analysis-service/.../TopologyQueryService.java` are Postgres recursive CTEs with an
ARRAY simple-path cycle guard and a depth cap. This harness runs those EXACT queries against
a real Postgres over a set of seeded graphs and diffs the result against an INDEPENDENT pure
Python reference (reverse/forward BFS + SCCs). A mismatch means the SQL and the intended graph
semantics have diverged — the whole point of a differential test.

It talks to Postgres purely through `psql` (no Python driver needed). Point it at any database:

    # Against a compose Postgres:
    MENDR_DIFFTEST_PSQL='docker compose exec -T postgres psql -U admin -d selfhealing' \
        python3 scripts/topology_cte_difftest.py

    # Against a throwaway container (what CI / this harness's self-test uses):
    MENDR_DIFFTEST_PSQL='docker exec -i <cid> psql -U postgres -d postgres' \
        python3 scripts/topology_cte_difftest.py

    # Or a DSN:
    MENDR_DIFFTEST_PSQL='psql postgresql://user:pass@localhost:5432/db' \
        python3 scripts/topology_cte_difftest.py

Exit code 0 = all graphs matched; nonzero = a divergence (printed).
"""
from __future__ import annotations

import os
import shlex
import subprocess
import sys
from collections import defaultdict, deque

TENANT = "00000000-0000-0000-0000-0000000000aa"
MAX_DEPTH = 10

# ── EXACT CTEs copied from TopologyQueryService (tenant/seed/depth as literals here) ──

BLAST_CTE = """
WITH RECURSIVE blast AS (
    SELECT e.source_node_id AS affected, 1 AS depth,
           ARRAY[e.target_node_id, e.source_node_id] AS path
    FROM service_topology_edges e
    WHERE e.tenant_id = '{tenant}' AND e.target_node_id = {seed} AND e.valid_to IS NULL
  UNION ALL
    SELECT e.source_node_id, b.depth + 1, b.path || e.source_node_id
    FROM service_topology_edges e
    JOIN blast b ON e.target_node_id = b.affected
    WHERE e.tenant_id = '{tenant}' AND e.valid_to IS NULL
      AND e.source_node_id <> ALL(b.path)
      AND b.depth < {maxDepth}
)
SELECT n.service_name AS service, MIN(b.depth) AS depth
FROM blast b JOIN service_topology_nodes n ON n.id = b.affected
GROUP BY n.service_name
ORDER BY depth, service;
"""

ROOT_CTE = """
WITH RECURSIVE deps AS (
    SELECT e.target_node_id AS dep, 1 AS depth,
           ARRAY[e.source_node_id, e.target_node_id] AS path
    FROM service_topology_edges e
    WHERE e.tenant_id = '{tenant}' AND e.source_node_id = {seed} AND e.valid_to IS NULL
  UNION ALL
    SELECT e.target_node_id, d.depth + 1, d.path || e.target_node_id
    FROM service_topology_edges e
    JOIN deps d ON e.source_node_id = d.dep
    WHERE e.tenant_id = '{tenant}' AND e.valid_to IS NULL
      AND e.target_node_id <> ALL(d.path)
      AND d.depth < {maxDepth}
)
SELECT n.service_name AS service, MIN(d.depth) AS depth
FROM deps d JOIN service_topology_nodes n ON n.id = d.dep
GROUP BY n.service_name
ORDER BY depth, service;
"""

CYCLE_CTE = """
WITH RECURSIVE walk AS (
    SELECT e.source_node_id AS start, e.target_node_id AS node,
           ARRAY[e.source_node_id, e.target_node_id] AS path, false AS cycle
    FROM service_topology_edges e
    WHERE e.tenant_id = '{tenant}' AND e.valid_to IS NULL
  UNION ALL
    SELECT w.start, e.target_node_id, w.path || e.target_node_id,
           (e.target_node_id = w.start)
    FROM service_topology_edges e
    JOIN walk w ON e.source_node_id = w.node
    WHERE e.tenant_id = '{tenant}' AND e.valid_to IS NULL AND NOT w.cycle
      AND (e.target_node_id = w.start OR e.target_node_id <> ALL(w.path))
      AND array_length(w.path, 1) < {maxDepth}
)
SELECT array_to_string(path, ',') FROM walk WHERE cycle;
"""

SETUP = """
DROP TABLE IF EXISTS service_topology_edges;
DROP TABLE IF EXISTS service_topology_causal_edges;
DROP TABLE IF EXISTS service_topology_nodes;
CREATE TABLE service_topology_nodes (
    id BIGINT PRIMARY KEY, tenant_id UUID NOT NULL, service_name TEXT NOT NULL
);
CREATE TABLE service_topology_edges (
    id BIGSERIAL PRIMARY KEY, tenant_id UUID NOT NULL,
    source_node_id BIGINT NOT NULL, target_node_id BIGINT NOT NULL,
    valid_to TIMESTAMPTZ
);
"""


# ── graphs (name -> (nodes, directed edges src->tgt "A calls B", seed, expects_cycle)) ──

def _graphs():
    return [
        # simple chain: gateway -> order -> payment -> ledger
        ("chain", ["gateway", "order", "payment", "ledger"],
         [("gateway", "order"), ("order", "payment"), ("payment", "ledger")],
         "payment"),
        # diamond: order -> {payment, inventory} -> ledger
        ("diamond", ["order", "payment", "inventory", "ledger"],
         [("order", "payment"), ("order", "inventory"),
          ("payment", "ledger"), ("inventory", "ledger")],
         "order"),
        # 3-cycle: a -> b -> c -> a  (plus a tail d -> a)
        ("cycle3", ["a", "b", "c", "d"],
         [("a", "b"), ("b", "c"), ("c", "a"), ("d", "a")],
         "b"),
        # self-only leaf: seed with no deps but many callers
        ("fanin", ["hub", "u1", "u2", "u3"],
         [("u1", "hub"), ("u2", "hub"), ("u3", "hub")],
         "hub"),
    ]


# ── independent Python reference ──

def _bfs(adj, seed, max_depth):
    """Shortest-hop distances (1..max_depth) from seed over adj; seed itself excluded."""
    dist = {}
    q = deque([(seed, 0)])
    seen = {seed}
    while q:
        node, d = q.popleft()
        if d >= max_depth:
            continue
        for nxt in adj.get(node, ()):
            if nxt not in seen:
                seen.add(nxt)
                dist[nxt] = d + 1
                q.append((nxt, d + 1))
    return {k: v for k, v in dist.items() if 1 <= v <= max_depth}


def _sccs(nodes, edges):
    """Tarjan SCCs; return list of node-frozensets that are cyclic (size>1 or self-loop)."""
    adj = defaultdict(list)
    selfloops = set()
    for s, t in edges:
        adj[s].append(t)
        if s == t:
            selfloops.add(s)
    index = {}
    low = {}
    on = set()
    stack = []
    out = []
    counter = [0]

    def strong(v):
        index[v] = low[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on.add(v)
        for w in adj.get(v, ()):
            if w not in index:
                strong(w)
                low[v] = min(low[v], low[w])
            elif w in on:
                low[v] = min(low[v], index[w])
        if low[v] == index[v]:
            comp = []
            while True:
                w = stack.pop()
                on.discard(w)
                comp.append(w)
                if w == v:
                    break
            out.append(comp)

    for n in nodes:
        if n not in index:
            strong(n)
    cyclic = []
    for comp in out:
        if len(comp) > 1 or (len(comp) == 1 and comp[0] in selfloops):
            cyclic.append(frozenset(comp))
    return cyclic


# ── psql plumbing ──

def _psql_cmd():
    raw = os.environ.get("MENDR_DIFFTEST_PSQL")
    if raw:
        return shlex.split(raw)
    dsn = os.environ.get("DATABASE_URL")
    return ["psql", dsn] if dsn else ["psql"]


def run_sql(sql, tuples_only=True):
    cmd = _psql_cmd() + ["-v", "ON_ERROR_STOP=1", "-q"]
    if tuples_only:
        cmd += ["-t", "-A", "-F", "|"]
    proc = subprocess.run(cmd, input=sql, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed: {proc.stderr.strip()}\nSQL:\n{sql}")
    return proc.stdout


def _seed_sql(node_ids, edges):
    lines = [SETUP]
    for name, nid in node_ids.items():
        lines.append(
            f"INSERT INTO service_topology_nodes (id, tenant_id, service_name) "
            f"VALUES ({nid}, '{TENANT}', '{name}');")
    for s, t in edges:
        lines.append(
            f"INSERT INTO service_topology_edges (tenant_id, source_node_id, target_node_id, valid_to) "
            f"VALUES ('{TENANT}', {node_ids[s]}, {node_ids[t]}, NULL);")
    return "\n".join(lines)


def _parse_depth_rows(out):
    result = {}
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        svc, depth = line.split("|")
        result[svc] = int(depth)
    return result


# ── driver ──

def run() -> int:
    failures = []
    for name, nodes, edges, seed in _graphs():
        node_ids = {n: i + 1 for i, n in enumerate(nodes)}
        run_sql(_seed_sql(node_ids, edges), tuples_only=False)

        fwd = defaultdict(list)
        rev = defaultdict(list)
        for s, t in edges:
            fwd[s].append(t)
            rev[t].append(s)

        # blast radius = backward reachability
        expected_blast = _bfs(rev, seed, MAX_DEPTH)
        got_blast = _parse_depth_rows(run_sql(
            BLAST_CTE.format(tenant=TENANT, seed=node_ids[seed], maxDepth=MAX_DEPTH)))

        # root cause = forward reachability
        expected_root = _bfs(fwd, seed, MAX_DEPTH)
        got_root = _parse_depth_rows(run_sql(
            ROOT_CTE.format(tenant=TENANT, seed=node_ids[seed], maxDepth=MAX_DEPTH)))

        # cycles: distinct member-sets
        expected_cycles = {frozenset(c) for c in _sccs(nodes, edges)}
        id_to_name = {v: k for k, v in node_ids.items()}
        got_cycle_sets = set()
        for line in run_sql(CYCLE_CTE.format(tenant=TENANT, maxDepth=MAX_DEPTH)).splitlines():
            line = line.strip()
            if not line:
                continue
            members = frozenset(id_to_name[int(x)] for x in line.split(","))
            got_cycle_sets.add(members)

        for label, exp, got in (
            (f"{name}:blast", expected_blast, got_blast),
            (f"{name}:root", expected_root, got_root),
        ):
            if exp == got:
                print(f"  OK  {label}: {got}")
            else:
                failures.append((label, exp, got))
                print(f" FAIL {label}: expected={exp} got={got}")

        if expected_cycles == got_cycle_sets:
            print(f"  OK  {name}:cycles: {[sorted(c) for c in got_cycle_sets]}")
        else:
            failures.append((f"{name}:cycles", expected_cycles, got_cycle_sets))
            print(f" FAIL {name}:cycles: expected={expected_cycles} got={got_cycle_sets}")

    if failures:
        print(f"\nDIFFERENTIAL MISMATCH in {len(failures)} check(s).")
        return 1
    print("\nAll topology CTE differential checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(run())
    except Exception as e:  # surface psql/connection errors clearly
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(2)
