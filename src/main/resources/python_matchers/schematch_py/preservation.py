"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import numpy as np

def preserve(q, rows, cols, fs, ft, lam):
    from scipy.optimize import linear_sum_assignment
    ri, ci = {x: i for i, x in enumerate(rows)}, {x: j for j, x in enumerate(cols)}
    S = {(ri[a], ri[b]): g for (a, b), g in fs.items() if a in ri and b in ri}
    T = {(ci[a], ci[b]): g for (a, b), g in ft.items() if a in ci and b in ci}
    r, c = linear_sum_assignment(-q)
    asg = dict(zip(r.tolist(), c.tolist()))
    top = {i: set(np.argsort(-q[i])[:3].tolist()) | ({asg[i]} if i in asg else set()) for i in range(q.shape[0])}

    def pair_term(a):
        s = 0.0
        inv = a
        for (i, k), g in S.items():
            if i in inv and k in inv:
                gt_ = T.get((inv[i], inv[k]))
                s += (g + gt_) / 2 if gt_ is not None else -g
        rev = {j: i for i, j in a.items()}
        for (j, l), g in T.items():
            if j in rev and l in rev and (rev[j], rev[l]) not in S:
                s -= g
        return s

    def obj(a):
        return sum(q[i, j] for i, j in a.items()) + lam * pair_term(a)
    cur = obj(asg)
    for _ in range(50):
        improved = False
        used = set(asg.values())
        for i in list(asg):
            for j in top[i]:
                if j == asg[i]:
                    continue
                cand = dict(asg)
                if j in used:
                    k = next(k for k, v in asg.items() if v == j)
                    cand[i], cand[k] = j, asg[i]
                else:
                    cand[i] = j
                v = obj(cand)
                if v > cur + 1e-12:
                    asg, cur, used, improved = cand, v, set(cand.values()), True
        if not improved:
            break
    return asg
