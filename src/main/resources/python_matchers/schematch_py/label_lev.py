"""Vendored from harness/regime_tables.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import numpy as np


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a or not b:
        return max(len(a), len(b))
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def label_matrix(source, target) -> np.ndarray:
    """Java `Levenshtein.compare`: raw labels, 1 - dist/max(len), float32."""
    out = np.zeros((len(source), len(target)), dtype=np.float32)
    for i, a in enumerate(source):
        for j, b in enumerate(target):
            denom = max(len(a), len(b))
            out[i, j] = (np.float32(1.0) - np.float32(levenshtein(a, b)) / np.float32(denom)
                         if denom else np.float32(1.0))
    return out
