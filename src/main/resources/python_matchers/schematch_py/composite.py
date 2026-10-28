"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
from pathlib import Path
import numpy as np
from .matrices import read_matrix

def unit(m: np.ndarray) -> np.ndarray:
    finite = m[np.isfinite(m)]
    if finite.size == 0:
        return np.zeros_like(m)
    lo, hi = float(finite.min()), float(finite.max())
    if hi <= lo:
        return np.zeros_like(m)
    return np.clip((np.nan_to_num(m, nan=lo) - lo) / (hi - lo), 0.0, 1.0)


def load(f: Path | None, rows=None, cols=None) -> np.ndarray | None:
    """A matrix, reordered to (rows, cols) when given; None if absent, non-finite or not alignable."""
    if f is None or not f.is_file():
        return None
    M, r, c = read_matrix(f)
    if rows is not None and (list(r) != list(rows) or list(c) != list(cols)):
        ri, ci = {x: i for i, x in enumerate(r)}, {x: j for j, x in enumerate(c)}
        if not (set(rows) <= set(ri) and set(cols) <= set(ci)):
            return None
        M = M[np.ix_([ri[x] for x in rows], [ci[x] for x in cols])]
    return M if np.isfinite(M).all() else None
