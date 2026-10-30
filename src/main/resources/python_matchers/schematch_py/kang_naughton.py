"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import numpy as np
from .assignment import sinkhorn

NULLS = {"", "null", "none", "nan", "na", "n/a", "-"}


EPS = 1e-12


def codes(column):
    """Integer codes for a column's values; -1 marks null."""
    out, index = np.empty(len(column), dtype=np.int64), {}
    for i, raw in enumerate(column):
        v = raw.strip()
        if v.lower() in NULLS:
            out[i] = -1
            continue
        out[i] = index.setdefault(v, len(index))
    return out, len(index)


def entropy(counts):
    total = counts.sum()
    if total <= 0:
        return 0.0
    p = counts[counts > 0] / total
    return float(-(p * np.log(p)).sum())


def mi_matrix(columns):
    """Normalised mutual information between every pair of columns.

    Computed on pair-complete rows: a pair where either side is null carries no
    information about their association and including it as a category would
    manufacture some.
    """
    coded = [codes(c) for c in columns]
    n = len(columns)
    out = np.zeros((n, n))
    for i in range(n):
        ci, ki = coded[i]
        for j in range(i + 1, n):
            cj, kj = coded[j]
            ok = (ci >= 0) & (cj >= 0)
            if ok.sum() < 2 or ki < 1 or kj < 1:
                continue
            a, b = ci[ok], cj[ok]
            joint = np.zeros((ki, kj))
            np.add.at(joint, (a, b), 1.0)
            hx = entropy(joint.sum(axis=1))
            hy = entropy(joint.sum(axis=0))
            hxy = entropy(joint.ravel())
            mi = hx + hy - hxy
            denom = min(hx, hy)
            v = 0.0 if denom <= EPS else float(np.clip(mi / denom, 0.0, 1.0))
            out[i, j] = out[j, i] = v
    return out


def kn_profile_scores(w1, w2):
    """Each column described by its sorted MI vector, compared by 1 - mean |d|.

    Sorting makes the descriptor permutation-invariant, which is what lets two
    schemas be compared before any correspondence is known.
    """
    def profile(w, width):
        n = w.shape[0]
        out = np.zeros((n, width))
        for i in range(n):
            row = np.sort(np.delete(w[i], i))[::-1]
            out[i, :min(width, row.size)] = row[:width]
        return out
    width = max(1, min(w1.shape[0], w2.shape[0]) - 1)
    p1, p2 = profile(w1, width), profile(w2, width)
    return np.clip(1.0 - np.abs(p1[:, None, :] - p2[None, :, :]).mean(axis=2), 0, 1)


def kn_graph_scores(w1, w2, betas=(0.5, 1.0, 2.0, 4.0), inner=10):
    """Soft quadratic assignment between two MI dependency graphs.

    The affinity between candidate pairs (i,a) and (j,b) is how closely the two
    edge weights agree. Unlike the sparse FD graphs of FINDINGS 26, an MI graph
    is dense, so agreement about a *missing* edge is not the dominant event and
    the failure mode diagnosed there does not apply here.
    """
    n1, n2 = w1.shape[0], w2.shape[0]
    affinity = 1.0 - np.abs(w1[:, :, None, None] - w2[None, None, :, :])
    affinity = np.clip(affinity, 0.0, 1.0)
    affinity[np.arange(n1), np.arange(n1), :, :] = 0.0
    m = np.full((n1, n2), 1.0 / max(n1, n2))
    for beta in betas:
        for _ in range(inner):
            score = np.einsum("ijab,jb->ia", affinity, m, optimize=True)
            peak = score.max()
            if peak > 0:
                score = score / peak
            m = sinkhorn(np.exp(beta * score))
    return m
