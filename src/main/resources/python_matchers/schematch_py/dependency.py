"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import numpy as np
from . import multitable as x105

def x105_dep(root, seed, ds, sc, rows, cols, iv):
    fs, us_, is_ = x105.profiles(root, ds, sc, "source")
    ft, ut_, it_ = x105.profiles(root, ds, sc, "target")
    S, T = x105.read_all(root, ds, sc, "source"), x105.read_all(root, ds, sc, "target")
    us_ = {k for k in us_ if k in S and sum(1 for v in S[k] if v) >= 20}
    ut_ = {k for k in ut_ if k in T and sum(1 for v in T[k] if v) >= 20}
    es, et = x105.entities(rows, fs, us_), x105.entities(cols, ft, ut_)
    M1, _ = x105.unit_term(iv, rows, cols, es, et, x105.unit_graph(rows, es, is_), x105.unit_graph(cols, et, it_), 0.0)
    ts, tt = {r: x105.table_of(r) for r in rows}, {c: x105.table_of(c) for c in cols}
    _, Tt = x105.unit_term(iv, rows, cols, ts, tt, x105.unit_graph(rows, ts, is_), x105.unit_graph(cols, tt, it_), 0.0)
    M3 = x105.m3_term(rows, cols, x105.fk_refs(set(rows), us_, is_), x105.fk_refs(set(cols), ut_, it_), us_ & set(rows), ut_ & set(cols), Tt)
    M4 = x105.m4_term(iv, rows, cols, S, T) if all(r in S for r in rows) and all(c in T for c in cols) else np.zeros(iv.shape)
    M = iv.copy()
    M = x105.add(M, iv, M1, ("tb", 0.2))
    M = x105.add(M, iv, M3, ("fixed", 0.1))
    M = x105.add(M, iv, M4, ("tb", 0.1))
    return M
