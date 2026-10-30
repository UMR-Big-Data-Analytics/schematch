"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import hashlib
import re
import unicodedata
import numpy as np
from scipy.sparse import csr_matrix

def normalise(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).casefold()
    return " ".join(re.findall(r"[^\W_]+", value, flags=re.UNICODE))


def sample_values(values, cap: int, seed: int):
    if cap < 1:
        raise ValueError("Value cap must be positive")
    # Sharing a hash priority across sides makes identical domains reproducible.
    # These are deliberately bounded proxies, not unbiased full-domain estimates.
    return sorted(set(values), key=lambda v: (
        hashlib.sha256(f"{seed}:{v}".encode("utf-8")).digest(), v))[:cap]


def exact_matrix(source, target, variant="soft"):
    if variant not in ("soft", "both", "jaccard"):
        raise ValueError(variant)
    source, target = [set(c) for c in source], [set(c) for c in target]
    out = np.zeros((len(source), len(target)))
    for i, a in enumerate(source):
        for j, b in enumerate(target):
            if not a or not b:
                continue
            shared = len(a & b)
            if variant == "soft":
                out[i, j] = .5 * (shared / len(a) + shared / len(b))
            elif variant == "both":
                out[i, j] = min(shared / len(a), shared / len(b))
            else:
                out[i, j] = shared / len(a | b)
    return out


def features(value: str, kind: str):
    if kind == "token":
        return set(value.split())
    if kind == "char3":
        padded = "^" + value + "$"
        return {padded[i:i+3] for i in range(max(0, len(padded) - 2))}
    raise ValueError(kind)


def vectorise(columns, kind):
    all_features = [[features(value, kind) for value in col] for col in columns]
    vocabulary = {token: i for i, token in enumerate(sorted({
        token for column in all_features for row in column for token in row}))}
    matrices = []
    for column in all_features:
        indices, offsets = [], [0]
        for row in column:
            indices.extend(sorted(vocabulary[token] for token in row))
            offsets.append(len(indices))
        matrices.append(csr_matrix((np.ones(len(indices)), indices, offsets),
                                   shape=(len(column), len(vocabulary)), dtype=np.float64))
    return matrices


def fuzzy_matrix(source, target, kind):
    vectors = vectorise([*source, *target], kind)
    source_vectors, target_vectors = vectors[:len(source)], vectors[len(source):]
    out = np.zeros((len(source), len(target)))
    for i, a in enumerate(source_vectors):
        for j, b in enumerate(target_vectors):
            if a.shape[0] == 0 or b.shape[0] == 0:
                continue
            intersection = (a @ b.T).toarray()
            na = np.asarray(a.sum(axis=1)).ravel()
            nb = np.asarray(b.sum(axis=1)).ravel()
            if kind == "token":
                denominator = na[:, None] + nb[None, :] - intersection
            else:
                denominator = np.sqrt(na[:, None] * nb[None, :])
            similarity = np.divide(intersection, denominator,
                                   out=np.zeros_like(intersection), where=denominator > 0)
            similarity = np.clip(similarity, 0, 1)
            out[i, j] = .5 * (similarity.max(axis=1).mean() + similarity.max(axis=0).mean())
    return out


def value_matrices(source, target, cap=128, seed=20260903):
    """Inputs are already non-null/trimmed values; return matrices and coverage."""
    raw = [[sorted(set(column)) for column in side] for side in (source, target)]
    normalised = [[[v for v in sorted({normalise(x) for x in column}) if v]
                   for column in side] for side in raw]
    samples = [[sample_values(column, cap, seed) for column in side] for side in normalised]
    matrices = {
        "exact_jaccard": exact_matrix(*raw, variant="jaccard"),
        "exact_anchor_both": exact_matrix(*raw, variant="both"),
        "raw_exact_soft_full": exact_matrix(*raw),
        "normalised_exact_soft_full": exact_matrix(*normalised),
        "normalised_exact_soft_capped": exact_matrix(*samples),
        "token_soft": fuzzy_matrix(*samples, kind="token"),
        "char3_soft": fuzzy_matrix(*samples, kind="char3"),
    }
    coverage = []
    for side_index, side in enumerate(("source", "target")):
        for index, values in enumerate(raw[side_index]):
            sampled = samples[side_index][index]
            normal = normalised[side_index][index]
            coverage.append({"side": side, "column": index, "raw_distinct": len(values),
                             "normalised_distinct": len(normal), "sampled": len(sampled),
                             "sampling_fraction": len(sampled) / len(normal) if normal else 1.0,
                             "sample_sha256": hashlib.sha256("\0".join(sampled).encode()).hexdigest(),
                             "max_value_length": max(map(len, values), default=0)})
    return matrices, coverage
