"""Vendored from harness/order_relation.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import numpy as np


MAX_ROWS = 20000


def to_ordered(values, numeric_only: bool):
    """Numeric codes for a column, or None when it carries no usable order.

    Numeric parsing is attempted first. Otherwise the values are ranked
    lexicographically, which is the right order for dates and padded
    identifiers and an arbitrary one for free text; `numeric_only` excludes
    that case rather than pretending it is meaningful.
    """
    cleaned = [v.strip() for v in values]
    try:
        arr = np.array([float(v) if v not in ("", "null", "NULL") else np.nan
                        for v in cleaned], dtype=float)
        if np.isfinite(arr).sum() >= max(2, 0.5 * len(arr)):
            return arr, True
    except (ValueError, TypeError):
        pass
    if numeric_only:
        return None, False
    distinct = sorted({v for v in cleaned if v})
    if len(distinct) < 2:
        return None, False
    rank = {v: i for i, v in enumerate(distinct)}
    arr = np.array([rank.get(v, np.nan) if v else np.nan for v in cleaned],
                   dtype=float)
    return arr, False


def order_matrix(columns, pairs_i, pairs_j):
    """mono(a -> b) for every ordered column pair, over sampled row pairs."""
    n = len(columns)
    out = np.full((n, n), np.nan, dtype=float)
    valid = [c is not None for c in columns]
    sampled = [c[pairs_i] if v else None for c, v in zip(columns, valid)]
    sampled_j = [c[pairs_j] if v else None for c, v in zip(columns, valid)]
    for a in range(n):
        if not valid[a]:
            continue
        ai, aj = sampled[a], sampled_j[a]
        strictly_less = (ai < aj) & np.isfinite(ai) & np.isfinite(aj)
        support = int(strictly_less.sum())
        if support == 0:
            continue
        for b in range(n):
            if not valid[b] or a == b:
                continue
            bi, bj = sampled[b][strictly_less], sampled_j[b][strictly_less]
            ok = np.isfinite(bi) & np.isfinite(bj)
            if not ok.any():
                continue
            # Non-discordant: B does not decrease where A increases.
            out[a, b] = float((bi[ok] <= bj[ok]).mean())
    return out
