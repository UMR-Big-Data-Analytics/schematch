"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import numpy as np
from .role_features import NULLS,vector_similarity,within_ind_evidence,within_ind_role_profile

RELATIONS = ("containment", "jaccard", "gcov", "uncertainty")


MI_EPS = 1e-12


def aligned_values(columns, owner_columns=None):
    """Retain positions; None denotes null. Ciphertext uses owner nullness only.

    Plaintext follows the existing gcov stripping/null policy. Ciphertext tokens
    are not normalised: the shipped MD5 strings are already canonical tokens.
    """
    if not columns or len({len(c) for c in columns}) != 1:
        raise ValueError("Expected a nonempty rectangular list of columns")
    if owner_columns is not None and (
        len(owner_columns) != len(columns)
        or any(len(a) != len(b) for a, b in zip(columns, owner_columns))
    ):
        raise ValueError("Owner null-mask dimensions differ")
    return [[(value if owner_columns is not None else value.strip())
             if raw.strip().lower() not in NULLS else None
             for value, raw in zip(column, owner)]
            for column, owner in zip(columns, columns if owner_columns is None else owner_columns)]


def _entropy(counts):
    counts = counts[counts > 0]
    if not counts.size:
        return 0.0
    p = counts / counts.sum()
    return float(-np.dot(p, np.log(p)))


def _codes(column):
    mapping = {}
    out = np.full(len(column), -1, dtype=np.int64)
    for i, value in enumerate(column):
        if value is not None:
            out[i] = mapping.setdefault(value, len(mapping))
    return out, len(mapping)


def uncertainty_relations(columns):
    """Return matrix[i,j]=I(X_i;X_j)/H(X_j) and pair-complete supports.

    Marginals and joint entropy use the SAME rows for each pair. Empty or
    constant referenced marginals get zero. Empirical U is uncorrected for
    finite-sample/cardinality bias: a unique determinant predicts everything.
    """
    if not columns or len({len(c) for c in columns}) != 1:
        raise ValueError("U requires rectangular row-aligned columns")
    coded = [_codes(c) for c in columns]
    width = len(columns)
    out = np.zeros((width, width))
    support = np.zeros((width, width), dtype=np.int64)
    for i, (x, _) in enumerate(coded):
        support[i, i] = int((x >= 0).sum())
        for j in range(i + 1, width):
            y, ny = coded[j]
            valid = (x >= 0) & (y >= 0)
            n = int(valid.sum())
            support[i, j] = support[j, i] = n
            if n < 2:
                continue
            a, b = x[valid], y[valid]
            hx, hy = _entropy(np.bincount(a)), _entropy(np.bincount(b))
            joint_counts = np.unique(a * ny + b, return_counts=True)[1]
            mi = hx + hy - _entropy(joint_counts)
            if mi <= MI_EPS:
                continue
            if hy > MI_EPS:
                out[i, j] = np.clip(mi / hy, 0, 1)
            if hx > MI_EPS:
                out[j, i] = np.clip(mi / hx, 0, 1)
    return out, support


def relation_matrices(columns, owner_columns=None):
    aligned = aligned_values(columns, owner_columns)
    clean = [[v for v in col if v is not None] for col in aligned]
    sets = [set(c) for c in clean]
    width = len(sets)
    containment = np.zeros((width, width))
    jaccard = np.zeros_like(containment)
    for i, a in enumerate(sets):
        for j in range(i + 1, width):
            b = sets[j]
            k = len(a & b)
            containment[i, j] = k / len(a) if a else 0.0
            containment[j, i] = k / len(b) if b else 0.0
            jaccard[i, j] = jaccard[j, i] = k / len(a | b) if a or b else 0.0
    gcov, _ = within_ind_evidence(clean)
    uncertainty, support = uncertainty_relations(aligned)
    relations = dict(zip(RELATIONS, (containment, jaccard, gcov, uncertainty)))
    offdiag = ~np.eye(width, dtype=bool)
    diagnostics = {
        "rows": len(columns[0]), "columns": width,
        "non_null_counts": [len(c) for c in clean],
        "distinct_counts": [len(s) for s in sets],
        "empirical_key_columns": sum(len(s) == len(c) and len(c) > 1 for s, c in zip(sets, clean)),
        "constant_or_empty_columns": sum(len(s) <= 1 for s in sets),
        "pair_complete_support_min": int(support[offdiag].min()) if width > 1 else 0,
        "pair_complete_support_median": float(np.median(support[offdiag])) if width > 1 else 0.0,
        "relations": {name: {
            "positive_edge_fraction": float((m[offdiag] > 0).mean()) if width > 1 else 0.0,
            "mean_strength": float(m[offdiag].mean()) if width > 1 else 0.0,
            "unit_edge_fraction": float((m[offdiag] >= 1 - MI_EPS).mean()) if width > 1 else 0.0,
        } for name, m in relations.items()},
    }
    return relations, support, diagnostics


def role_scores(left, right):
    """Unchanged 12-D descriptor and 1 - mean L1 distance from prior gcov runs."""
    return vector_similarity(within_ind_role_profile(left), within_ind_role_profile(right))
