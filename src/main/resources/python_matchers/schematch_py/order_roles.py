"""Vendored from harness/order_role.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import numpy as np


QUANTILES = (0.1, 0.25, 0.5, 0.75, 0.9)


def role_profile(matrix, n_cols):
    """Outgoing and incoming quantiles of the relation, per column.

    Same shape as the descriptor section 11 used for the equality-based
    relations, so the substitution is controlled. A column with no usable
    order contributes an all-zero profile rather than being dropped, which
    keeps the matrix aligned with the schema.
    """
    prof = np.zeros((n_cols, 2 * len(QUANTILES) + 1))
    if matrix is None:
        return prof
    for i in range(min(n_cols, matrix.shape[0])):
        out = matrix[i][np.isfinite(matrix[i])]
        inc = matrix[:, i][np.isfinite(matrix[:, i])]
        if out.size:
            prof[i, :len(QUANTILES)] = np.quantile(out, QUANTILES)
        if inc.size:
            prof[i, len(QUANTILES):2 * len(QUANTILES)] = np.quantile(inc, QUANTILES)
        prof[i, -1] = 1.0 if out.size or inc.size else 0.0
    return prof
