"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
from collections import defaultdict
import numpy as np
from . import multitable as x105
from . import preservation as s7

NAME_W, TAU, CAP, TOPC = 0.25, 0.9, 3, 3

def side_graph(rt, ds, sc, side, labels, key_equiv=True, virtual=True):
    fds, uccs, _ = x105.profiles(rt, ds, sc, side)
    V = x105.read_all(rt, ds, sc, side)
    lab = [l for l in labels if l in V]
    D = {l: set(v for v in V[l] if v) for l in lab}
    nn = {l: sum(1 for v in V[l] if v) for l in lab}
    tables = sorted({x105.table_of(l) for l in labels})
    keys = {l for l in lab if l in uccs and nn[l] >= 20 and len(D[l]) / nn[l] >= 0.99}
    idclass = {t: sorted(k for k in keys if x105.table_of(k) == t) for t in tables}
    if not key_equiv:
        idclass = {t: v[:1] for t, v in idclass.items()}
    unit = {l: x105.table_of(l) for l in labels}
    vparent = {}
    if virtual:
        det = defaultdict(dict)
        for lhs, rhs, p, g in fds:
            if len(lhs) != 1 or p < 0.99 or g < 0.05:
                continue
            d = lhs[0]
            if d not in D or rhs not in D or d in keys or d == rhs or x105.table_of(d) != x105.table_of(rhs):
                continue
            if nn[d] and len(D[d]) / nn[d] >= 0.99:
                continue
            det[d][rhs] = max(g, det[d].get(rhs, 0.0))
        parent = {d: d for d in det}

        def find(x):
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x
        for d in det:
            for x in det[d]:
                if x in det and d in det[x]:
                    parent[find(d)] = find(x)
        cls = defaultdict(set)
        for d in det:
            cls[find(d)].add(d)
        for K in cls.values():
            deps = {x for d in K for x in det[d] if x not in K and not any(x in det and d2 in det[x] for d2 in K)}
            if len(deps) < 2:
                continue
            eid = f"{x105.table_of(min(K))}#{min(K)}"
            vparent[eid] = x105.table_of(min(K))
            for col in K | deps:
                if col in unit and unit[col] == x105.table_of(col):
                    unit[col] = eid
    refs = {}
    for x in lab:
        if len(D[x]) < 2:
            continue
        best = None
        for t, ks in idclass.items():
            if t == x105.table_of(x):
                continue
            for k in ks:
                cov = len(D[x] & D[k]) / len(D[x])
                if cov >= 0.9 and (best is None or cov > best[1]):
                    best = (t, cov)
        if best:
            refs[x] = best
    eref = defaultdict(float)
    for x, (t, cov) in refs.items():
        eref[(unit[x], t)] = max(eref[(unit[x], t)], cov)
    for eid, t in vparent.items():
        eref[(t, eid)] = 1.0
    ents = sorted(set(unit.values()) | set(tables))
    return {"unit": unit, "ents": ents, "refs": refs, "eref": dict(eref), "tables": tables, "keys": sorted(keys),
            "virtual": sorted(vparent)}


def ent_tokens(e):
    if "#" in e:
        t, d = e.split("#", 1)
        return x105.qtokens(f"{t}.{d.split('.', 1)[1]}")
    return x105.qtokens(f"{e}.x") - {"x"}


def align(base, rows, cols, gs, gt, lam, greedy=False):
    from scipy.optimize import Bounds, LinearConstraint, milp
    Es, Et = gs["ents"], gt["ents"]
    ri, ci = defaultdict(list), defaultdict(list)
    for i, r in enumerate(rows):
        ri[gs["unit"][r]].append(i)
    for j, c in enumerate(cols):
        ci[gt["unit"][c]].append(j)
    Es, Et = [e for e in Es if ri[e]], [e for e in Et if ci[e]]
    U = np.zeros((len(Es), len(Et)))
    for a, E in enumerate(Es):
        for b, F in enumerate(Et):
            blk = np.clip(base[np.ix_(ri[E], ci[F])], 0, None)
            A = 0.5 * (blk.max(axis=1).mean() + blk.max(axis=0).mean())
            ta, tb = ent_tokens(E), ent_tokens(F)
            U[a, b] = A + NAME_W * (len(ta & tb) / len(ta | tb) if ta | tb else 0.0)
    rmax, cmax = U.max(axis=1, keepdims=True), U.max(axis=0, keepdims=True)
    C = U - TAU * np.maximum(rmax, cmax)
    cand = set()
    for a in range(len(Es)):
        cand |= {(a, b) for b in np.argsort(-U[a])[:TOPC]}
    for b in range(len(Et)):
        cand |= {(a, b) for a in np.argsort(-U[:, b])[:TOPC]}
    cand = sorted(cand)
    idx = {p: k for k, p in enumerate(cand)}
    ers = [(Es.index(e), Es.index(f), w) for (e, f), w in gs["eref"].items() if e in Es and f in Es]
    ert = [(Et.index(e), Et.index(f), w) for (e, f), w in gt["eref"].items() if e in Et and f in Et]
    zs = []
    for a1, a2, w1 in ers:
        for b1, b2, w2 in ert:
            if (a1, b1) in idx and (a2, b2) in idx:
                zs.append((idx[(a1, b1)], idx[(a2, b2)], (w1 + w2) / 2))
    if greedy:
        chosen, deg_s, deg_t = set(), defaultdict(int), defaultdict(int)
        while True:
            best = None
            for k, (a, b) in enumerate(cand):
                if k in chosen or deg_s[a] >= CAP or deg_t[b] >= CAP:
                    continue
                gain = C[a, b] + lam * sum(w for k1, k2, w in zs if (k1 == k and k2 in chosen) or (k2 == k and k1 in chosen))
                if gain > 1e-12 and (best is None or gain > best[0]):
                    best = (gain, k)
            if best is None:
                break
            k = best[1]
            chosen.add(k); deg_s[cand[k][0]] += 1; deg_t[cand[k][1]] += 1
        sel = [cand[k] for k in chosen]
    else:
        ny, nz = len(cand), len(zs)
        cvec = -np.concatenate([[C[a, b] for a, b in cand], [lam * w for _, _, w in zs]])
        rows_, lo, hi = [], [], []
        for a in range(len(Es)):
            r = np.zeros(ny + nz); r[[idx[p] for p in cand if p[0] == a]] = 1; rows_.append(r); lo.append(0); hi.append(CAP)
        for b in range(len(Et)):
            r = np.zeros(ny + nz); r[[idx[p] for p in cand if p[1] == b]] = 1; rows_.append(r); lo.append(0); hi.append(CAP)
        for k, (k1, k2, _) in enumerate(zs):
            for kk in (k1, k2):
                r = np.zeros(ny + nz); r[ny + k] = 1; r[kk] = -1; rows_.append(r); lo.append(-np.inf); hi.append(0)
        res = milp(cvec, constraints=LinearConstraint(np.array(rows_), lo, hi), integrality=np.ones(ny + nz),
                   bounds=Bounds(0, 1))
        y = np.round(res.x[:ny]) if res.success else np.zeros(ny)
        sel = [cand[k] for k in range(ny) if y[k] > 0.5]
    return [(Es[a], Et[b]) for a, b in sel], ri, ci


def output(cell, gs, gt, lam, mu, pres, greedy=False, use_refs=True):
    from scipy.optimize import linear_sum_assignment
    base = cell["base"]
    if len(gs["tables"]) <= 1 and len(gt["tables"]) <= 1:
        return base.copy(), []
    pairs, ri, ci = align(base, cell["rows"], cell["cols"], gs, gt, lam if use_refs else 0.0, greedy)
    aligned = {(e, f) for e, f in pairs}
    Bm = np.zeros(base.shape)
    Am = np.zeros(base.shape)
    for e, f in pairs:
        I, J = ri[e], ci[f]
        Bm[np.ix_(I, J)] = 1.0
        S = base[np.ix_(I, J)].copy()
        if use_refs:
            for a, i in enumerate(I):
                rs = gs["refs"].get(cell["rows"][i])
                if not rs:
                    continue
                for b, j in enumerate(J):
                    rt_ = gt["refs"].get(cell["cols"][j])
                    if rt_ and (rs[0], rt_[0]) in aligned:
                        S[a, b] += 1.0
        if pres:
            rows_b, cols_b = [cell["rows"][i] for i in I], [cell["cols"][j] for j in J]
            fs = {k: v for k, v in cell["fds_s"].items() if k[0] in rows_b and k[1] in rows_b}
            ft = {k: v for k, v in cell["fds_t"].items() if k[0] in cols_b and k[1] in cols_b}
            Su = (S - S.min()) / (np.ptp(S) or 1.0)
            asg = s7.preserve(Su, rows_b, cols_b, fs, ft, 0.1)
        else:
            r, c = linear_sum_assignment(-S)
            asg = dict(zip(r.tolist(), c.tolist()))
        for a, b in asg.items():
            Am[I[a], J[b]] = 1.0
    return base + mu * Bm + mu * Am, pairs
