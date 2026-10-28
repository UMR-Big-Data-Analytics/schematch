"""Vendored from harness/anchor_generalization.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import numpy as np

from .anchors import anchor_score


def bare(name: str) -> str:
    """Column name without its table qualifier.

    `column_values` and `parse_fds` both key by `<table>.<column>`, and in the
    prepared layout the table stem is literally `source` or `target`, while the
    cached seed matrices carry bare column names. Left unreconciled, every
    correspondence lookup misses, the oracle arm comes back empty and all five
    arms collapse onto the baseline -- a silent null rather than an error, and
    the same failure mode as the FD annotation defect in FINDINGS 15.
    """
    return name.split(".")[-1]


def anchor_matrix(s_values, t_values, s_cols, t_cols, variant="gcov_both"):
    """Cross-instance inclusion evidence; no labels, no ground truth."""
    pool = len(set().union(*s_values.values(), *t_values.values())) \
        if s_values and t_values else 0
    m = np.zeros((len(s_cols), len(t_cols)))
    for i, a in enumerate(s_cols):
        va = s_values.get(a, set())
        for j, b in enumerate(t_cols):
            m[i, j] = anchor_score(va, t_values.get(b, set()), variant, pool)
    return m
