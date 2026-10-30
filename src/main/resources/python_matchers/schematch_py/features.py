"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import re
import numpy as np
import pandas as pd

def tokens(name: str) -> list[str]:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", s)
    return [t for t in re.split(r"[^A-Za-z0-9]+", s.lower()) if t]


def num(vals: list[str]) -> np.ndarray:
    out = np.full(len(vals), np.nan)
    for k, v in enumerate(vals):
        try:
            out[k] = float(v.replace(",", "")) if v else np.nan
        except ValueError:
            pass
    return out


def mutual_top1(B: np.ndarray) -> list[tuple[int, int, float]]:
    out = []
    if B.size == 0:
        return out
    ra, ca = B.argmax(axis=1), B.argmax(axis=0)
    for i, j in enumerate(ra):
        if ca[j] == i:
            r2 = np.partition(B[i], -2)[-2] if B.shape[1] > 1 else 0.0
            c2 = np.partition(B[:, j], -2)[-2] if B.shape[0] > 1 else 0.0
            out.append((i, int(j), float(B[i, j] - max(r2, c2))))
    return out


def gpdep(x: np.ndarray, y: np.ndarray) -> float:
    """gpdep(X -> Y) on codes: pdep minus its expectation under independence (Mandros et al.), clipped to [0, 1]."""
    n = len(x)
    if n < 2:
        return 0.0
    py = np.bincount(y) / n
    p0 = float((py ** 2).sum())
    df = pd.DataFrame({"x": x, "y": y})
    cxy = df.groupby(["x", "y"]).size()
    cx = df.groupby("x").size()
    pd_ = float(((cxy ** 2) / cx.reindex(cxy.index.get_level_values(0)).to_numpy()).sum() / n)
    kx = len(cx)
    exp = p0 + (kx - 1) / (n - 1) * (1 - p0)
    return float(np.clip(pd_ - exp, 0.0, 1.0))
