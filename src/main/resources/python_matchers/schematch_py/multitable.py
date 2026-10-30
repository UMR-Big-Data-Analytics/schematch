"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import csv
import re
from collections import defaultdict
import numpy as np
import pandas as pd
from . import features as x102

MAXROWS = 20_000


FD_LINE = re.compile(r"^\[(?P<lhs>[^\]]*)\]\s*-->\s*(?P<rhs>\S+)\s*\(pdep\s+(?P<p>[-\d.eE]+),\s*(?P<g>[-\d.eE]+)\)")


IND_LINE = re.compile(r"^\[(?P<lhs>[^\],]+)\]\s*\[=\s*\[(?P<rhs>[^\],]+)\]")


UCC_LINE = re.compile(r"^\[(?P<cols>[^\]]*)\]")


def lab(q):
    t, c = q.strip().split(".csv.", 1)
    return f"{t}.{c}"


def table_of(label):
    return label.split(".", 1)[0]


def profiles(root, ds, sc, side):
    meta = root / ds / sc / "metadata" / side
    fds, uccs, inds = [], set(), []
    for f in sorted(meta.rglob("FD_results.txt")):
        for line in f.read_text(encoding="utf-8").splitlines():
            m = FD_LINE.match(line.strip())
            if m:
                lhs = [lab(x) for x in m.group("lhs").split(",") if x.strip()]
                fds.append((tuple(lhs), lab(m.group("rhs")), float(m.group("p")), max(float(m.group("g")), 0.0)))
    for f in sorted(meta.rglob("UCC_results.txt")):
        for line in f.read_text(encoding="utf-8").splitlines():
            m = UCC_LINE.match(line.strip())
            if m and m.group("cols").strip() and "," not in m.group("cols"):
                uccs.add(lab(m.group("cols")))
    fi = meta / "inds.txt"
    if fi.is_file():
        for line in fi.read_text(encoding="utf-8").splitlines():
            m = IND_LINE.match(line.strip())
            if m:
                inds.append((lab(m.group("lhs")), lab(m.group("rhs"))))
    return fds, uccs, inds


def read_all(root, ds, sc, side):
    out = {}
    for f in sorted((root / ds / sc / side).glob("*.csv")):
        with open(f, encoding="utf-8", errors="replace", newline="") as fh:
            rd = csv.reader(fh)
            header = next(rd, None) or []
            data = [row for _, row in zip(range(MAXROWS), rd)]
        for j, h in enumerate(header):
            out.setdefault(f"{f.stem}.{h}", [(row[j] if j < len(row) else "").strip() for row in data])
    return out


def entities(labels, fds, uccs):
    """label -> unit id (table or table#entity)."""
    cols = set(labels)
    best = {}
    det_fd = defaultdict(dict)
    for lhs, rhs, p, g in fds:
        if len(lhs) != 1 or p < 0.99 or g < 0.05:
            continue
        d = lhs[0]
        if d not in cols or rhs not in cols or d in uccs or table_of(d) != table_of(rhs):
            continue
        det_fd[d][rhs] = max(g, det_fd[d].get(rhs, 0.0))
    dets = list(det_fd)
    parent = {d: d for d in dets}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for d in dets:
        for x in det_fd[d]:
            if x in det_fd and d in det_fd[x]:
                parent[find(d)] = find(x)
    cls = defaultdict(set)
    for d in dets:
        cls[find(d)].add(d)
    unit = {c: table_of(c) for c in labels}
    for root_, K in cls.items():
        eid = f"{table_of(root_)}#{min(K)}"
        for d in K:
            unit[d] = eid
    for x in labels:
        if any(x in K for K in cls.values()):
            continue
        cand = []
        for root_, K in cls.items():
            g = max((det_fd[d].get(x, 0.0) for d in K), default=0.0)
            back = any(x in det_fd and d in det_fd[x] for d in K)
            if g > 0 and not back:
                cand.append((g, f"{table_of(root_)}#{min(K)}"))
        if cand:
            unit[x] = max(cand)[1]
    return unit


def unit_graph(labels, unit, inds):
    nb = defaultdict(set)
    for a, b in inds:
        if a in unit and b in unit and unit[a] != unit[b]:
            nb[unit[a]].add(unit[b]); nb[unit[b]].add(unit[a])
    for c in labels:
        u, t = unit[c], table_of(c)
        if u != t:
            nb[u].add(t); nb[t].add(u)
    return nb


def unit_term(B, rows, cols, us, ut, nbs, nbt, lam):
    ri, ci = defaultdict(list), defaultdict(list)
    for i, r in enumerate(rows):
        ri[us[r]].append(i)
    for j, c in enumerate(cols):
        ci[ut[c]].append(j)
    T1 = {}
    for a, I in ri.items():
        for b, J in ci.items():
            blk = np.clip(B[np.ix_(I, J)], 0, None)
            T1[(a, b)] = 0.5 * (blk.max(axis=1).mean() + blk.max(axis=0).mean())
    T = {}
    for (a, b), v in T1.items():
        t2 = []
        if lam > 0:
            na, nb_ = [x for x in nbs.get(a, ()) if x in ri], [y for y in nbt.get(b, ()) if y in ci]
            if na and nb_:
                d1 = np.mean([max(T1[(x, y)] for y in nb_) for x in na])
                d2 = np.mean([max(T1[(x, y)] for x in na) for y in nb_])
                t2 = [0.5 * (d1 + d2)]
        T[(a, b)] = v + lam * (t2[0] if t2 else 0.0)
    top = max(T.values()) if T else 0.0
    C = np.zeros(B.shape)
    if top > 0:
        for (a, b), v in T.items():
            C[np.ix_(ri[a], ci[b])] = v / top
    return C, {k: v / top for k, v in T.items()} if top > 0 else {}


def fk_refs(labels, uccs, inds):
    ref = {}
    for a, b in inds:
        if a in labels and b in uccs and table_of(a) != table_of(b) and a not in uccs:
            ref.setdefault(a, set()).add(table_of(b))
    return ref


def m3_term(rows, cols, ref_s, ref_t, keys_s, keys_t, Tt):
    R = np.zeros((len(rows), len(cols)))
    for i, r in enumerate(rows):
        for j, c in enumerate(cols):
            if r in ref_s and c in ref_t:
                R[i, j] = max(Tt.get((a, b), 0.0) for a in ref_s[r] for b in ref_t[c])
            elif r in keys_s and c in keys_t:
                R[i, j] = Tt.get((table_of(r), table_of(c)), 0.0)
    return R


def fan(x, y):
    df = pd.DataFrame({"x": x, "y": y})
    m = df.groupby("x").y.nunique().max()
    k = df.y.nunique()
    return float(np.log1p(m) / np.log1p(k)) if k > 0 else 0.0


def pair_features(V, c, a, codes, nums):
    f = np.zeros(10)
    f[0], f[1] = x102.gpdep(codes[c], codes[a]), x102.gpdep(codes[a], codes[c])
    f[2], f[3] = fan(codes[c], codes[a]), fan(codes[a], codes[c])
    x, y = nums[c], nums[a]
    ok = np.isfinite(x) & np.isfinite(y)
    if ok.sum() >= 20:
        rho = pd.Series(x[ok]).rank().corr(pd.Series(y[ok]).rank())
        f[4] = (rho + 1) / 2 if np.isfinite(rho) else 0.5
        f[5], f[6], f[7] = (x[ok] < y[ok]).mean(), (x[ok] == y[ok]).mean(), (x[ok] > y[ok]).mean()
        f[8] = 1.0
    else:
        f[4] = 0.5
    return f


def m4_term(B, rows, cols, S, T):
    an = [(i, j) for i, j, m in x102.mutual_top1(B) if m >= 0.1]
    by = defaultdict(list)
    for i, j in sorted(an, key=lambda p: -B[p]):
        k = (table_of(rows[i]), table_of(cols[j]))
        if len(by[k]) < 5:
            by[k].append((rows[i], cols[j]))
    if not by:
        return np.zeros(B.shape)

    def prep(V, labels):
        return ({c: pd.factorize(pd.Series(V[c]))[0] for c in labels}, {c: x102.num(V[c]) for c in labels})
    cs, ns = prep(S, rows)
    ct, nt = prep(T, cols)
    cache = {}

    def feat(side, c, a):
        key = (side, c, a)
        if key not in cache:
            cache[key] = pair_features(None, c, a, cs if side == "s" else ct, ns if side == "s" else nt)
        return cache[key]
    R = np.zeros(B.shape)
    for i, r in enumerate(rows):
        for j, c in enumerate(cols):
            A = by.get((table_of(r), table_of(c)))
            if not A:
                continue
            R[i, j] = 1.0 - np.mean([np.abs(feat("s", r, a) - feat("t", c, b)).mean() for a, b in A])
    return R


def qtokens(label):
    t, c = label.split(".", 1)
    return {w[:-1] if len(w) > 3 and w.endswith("s") else w for w in x102.tokens(t) + x102.tokens(c)}


def q_term(rows, cols):
    A, B = [qtokens(r) for r in rows], [qtokens(c) for c in cols]
    return np.array([[len(a & b) / len(a | b) if a | b else 0.0 for b in B] for a in A])


def mask(B, d):
    return ((B >= B.max(axis=1, keepdims=True) - d) | (B >= B.max(axis=0, keepdims=True) - d)).astype(float)


def add(M, B, T, e):
    kind, x = e
    return M + (x * T if kind == "fixed" else x * mask(B, x) * T)
