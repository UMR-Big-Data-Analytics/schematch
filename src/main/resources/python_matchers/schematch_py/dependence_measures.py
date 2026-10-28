"""Vendored from harness/smx/dependence_corruption.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import numpy as np

from .relational_roles import MI_EPS, _codes, _entropy, aligned_values


MEASURES = ("binary_fd", "gpdep", "u", "nmi")


def dependence_matrices(columns):
    """Pair-complete non-null observations, unary edges, zero diagonal.

    gpdep is the project's UNNORMALISED pdep - expected_pdep, positive part,
    not a [0,1]-rescaled genuine score. Keep signed values for null diagnostics.
    U and geometric NMI are plug-in estimates, without bias adjustment.
    Both constant endpoints and n<2 are uninformative for every measure.
    A separate common nonkey mask removes pairs where EITHER endpoint is unique
    on that pair's actual support. Exact binary FDs use joint-support counts,
    not numerical proximity to pdep=1 or projected composite determinants.
    """
    aligned = aligned_values(columns)
    coded = [_codes(c) for c in aligned]
    width = len(columns)
    matrices = {name: np.zeros((width, width)) for name in MEASURES}
    support = np.zeros((width, width), dtype=np.int64)
    nonkey = np.zeros((width, width), dtype=bool)
    signed = np.zeros((width, width))
    distinct = np.zeros((width, width), dtype=np.int64)
    for i, (x, _) in enumerate(coded):
        support[i, i] = int((x >= 0).sum())
        distinct[i, i] = len(set(x[x >= 0]))
        for j in range(i + 1, width):
            y, ny_total = coded[j]
            valid = (x >= 0) & (y >= 0)
            n = int(valid.sum())
            support[i, j] = support[j, i] = n
            if n < 2:
                continue
            a, b = x[valid], y[valid]
            cx, cy = np.bincount(a), np.bincount(b)
            nx, ny = int(np.count_nonzero(cx)), int(np.count_nonzero(cy))
            distinct[i, j], distinct[j, i] = nx, ny
            hx, hy = _entropy(cx), _entropy(cy)
            if hx <= MI_EPS or hy <= MI_EPS:
                continue
            keys, counts = np.unique(a * ny_total + b, return_counts=True)
            ax, by = keys // ny_total, keys % ny_total
            mi = max(0.0, hx + hy - _entropy(counts))
            if mi <= MI_EPS:
                mi = 0.0
            matrices["u"][i, j], matrices["u"][j, i] = min(1., mi/hy), min(1., mi/hx)
            matrices["nmi"][i, j] = matrices["nmi"][j, i] = min(1., mi/np.sqrt(hx*hy))
            matrices["binary_fd"][i, j] = len(counts) == nx
            matrices["binary_fd"][j, i] = len(counts) == ny
            nonkey[i, j] = nonkey[j, i] = nx < n and ny < n
            for p, q, det_count, ref_counts, group_counts in ((i,j,nx,cy,cx[ax]), (j,i,ny,cx,cy[by])):
                pdep = float(np.sum(counts.astype(float)**2 / group_counts) / n)
                collision = float(np.sum((ref_counts/n)**2))
                expected = collision + (det_count-1)/(n-1)*(1-collision)
                g = pdep - expected
                signed[p, q] = 0.0 if abs(g) <= MI_EPS else g
                matrices["gpdep"][p, q] = g if g > MI_EPS else 0.0
    return {"raw_"+k: v for k, v in matrices.items()} | {
        "nonkey_"+k: v*nonkey for k, v in matrices.items()
    }, {"support": support, "nonkey": nonkey, "distinct": distinct, "signed_gpdep": signed}
