"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import numpy as np

EPS = 1e-12


def sinkhorn(m, iters=20):
    """Toward a doubly stochastic matrix, as graduated assignment requires."""
    out = np.clip(m, EPS, None)
    for _ in range(iters):
        out = out / np.maximum(out.sum(axis=1, keepdims=True), EPS)
        out = out / np.maximum(out.sum(axis=0, keepdims=True), EPS)
    return out
