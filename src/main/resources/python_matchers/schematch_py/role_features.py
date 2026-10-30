"""Bundled matcher implementation; see SOURCES.json for provenance."""
from __future__ import annotations
import csv
import hashlib
import hmac
import math
from pathlib import Path
from collections import Counter
import numpy as np
from scipy.stats import binom

QP = np.linspace(0.0, 1.0, 5)


NULLS = {"", "null", "none", "nan", "na", "n/a", "-"}


def binomial_significance(n: int, k: int, probability: float) -> float:
    """-log10 P[Bin(n, probability) >= k], capped only after log-space evaluation."""
    if n <= 0 or k <= 0 or probability >= 1.0:
        return 0.0
    probability = max(0.0, probability)
    if probability == 0.0:
        return 300.0
    if binom is None:  # conservative normal-free fallback
        return 0.0
    log_probability = float(binom.logsf(k - 1, n, probability))
    if not math.isfinite(log_probability):
        return 300.0
    return min(300.0, max(0.0, -log_probability / math.log(10.0)))


def within_ind_evidence(columns: list[list[str]]) -> tuple[np.ndarray, np.ndarray]:
    """Dense within-table IND gcov and significance for every ordered pair."""
    sets = [set(column) for column in columns]
    counters = [Counter(column) for column in columns]
    total = Counter()
    for counter in counters:
        total.update(counter)
    n_columns = len(columns)
    gcov = np.zeros((n_columns, n_columns))
    significance = np.zeros((n_columns, n_columns))
    for dependent in range(n_columns):
        source_values = sets[dependent]
        n = len(source_values)
        if n == 0:
            continue
        background = total.copy()
        background.subtract(counters[dependent])
        background_total = sum(count for count in background.values() if count > 0)
        for referenced in range(n_columns):
            if dependent == referenced:
                continue
            target_values = sets[referenced]
            k = len(source_values & target_values)
            if k == 0 or background_total <= 0:
                continue
            coverage = k / n
            b = sum(max(0, background[value]) for value in target_values) / background_total
            genuine = (coverage - b) / (1.0 - b) if b < 1.0 else 0.0
            gcov[dependent, referenced] = max(0.0, genuine)
            significance[dependent, referenced] = binomial_significance(n, k, b)
    return gcov, significance


def within_ind_role_profile(
    gcov: np.ndarray, significance: np.ndarray | None = None
) -> np.ndarray:
    """Permutation-invariant outgoing/incoming qualified-IND descriptor."""
    features = []
    n = gcov.shape[0]
    for column in range(n):
        row: list[float] = []
        directions = (
            (np.delete(gcov[column, :], column), np.delete(significance[column, :], column)
             if significance is not None else None),
            (np.delete(gcov[:, column], column), np.delete(significance[:, column], column)
             if significance is not None else None),
        )
        for strengths, sigs in directions:
            positive = strengths > 0
            values = strengths[positive]
            row.append(float(positive.mean()) if positive.size else 0.0)
            row.extend(np.quantile(values, QP) if values.size else np.zeros(len(QP)))
            if sigs is not None:
                bounded = np.log1p(np.minimum(sigs[positive], 300.0)) / math.log(301.0)
                row.extend(np.quantile(bounded, QP) if bounded.size else np.zeros(len(QP)))
                joint = values * bounded
                row.extend(np.quantile(joint, QP) if joint.size else np.zeros(len(QP)))
        features.append(row)
    return np.asarray(features)


def vector_similarity(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    distance = np.abs(left[:, None, :] - right[None, :, :]).mean(axis=2)
    return np.clip(1.0 - distance, 0.0, 1.0)


EXPERIMENT_HMAC_KEY = b"schematch-shared-key-ablation-v1"


def profile_quantiles(matrix: np.ndarray) -> np.ndarray:
    features = []
    quantiles = np.linspace(0.0, 1.0, 9)
    for i in range(matrix.shape[0]):
        out = np.delete(matrix[i, :], i) if matrix.shape[0] > 1 else np.zeros(1)
        inc = np.delete(matrix[:, i], i) if matrix.shape[0] > 1 else np.zeros(1)
        features.append(np.concatenate((np.quantile(out, quantiles), np.quantile(inc, quantiles))))
    return np.asarray(features)


def quantile_scores(source: np.ndarray, target: np.ndarray) -> np.ndarray:
    left, right = profile_quantiles(source), profile_quantiles(target)
    distance = np.abs(left[:, None, :] - right[None, :, :]).mean(axis=2)
    scale = float(distance.max())
    return 1.0 - distance / scale if scale > 0 else np.zeros(distance.shape)


def read_table(path: Path) -> tuple[list[str], list[list[str]]]:
    with path.open(newline="", encoding="utf-8-sig", errors="replace") as fh:
        rows = list(csv.reader(fh))
    if not rows:
        raise ValueError(f"empty table: {path}")
    header = [x.strip() for x in rows[0]]
    width = len(header)
    data = [row + [""] * (width - len(row)) for row in rows[1:]]
    columns = [[row[i] if i < len(row) else "" for row in data] for i in range(width)]
    return header, columns


def scalar_similarity(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    return np.clip(1.0 - np.abs(left[:, None] - right[None, :]), 0.0, 1.0)


def pmf(column: list[str]) -> np.ndarray:
    if not column:
        return np.zeros(0)
    counts = sorted(Counter(column).values(), reverse=True)
    return np.asarray(counts, dtype=float) / len(column)


def pmf_scores(source: list[list[str]], target: list[list[str]]) -> np.ndarray:
    profiles_s = [pmf(col) for col in source]
    profiles_t = [pmf(col) for col in target]
    out = np.zeros((len(source), len(target)))
    for i, left in enumerate(profiles_s):
        for j, right in enumerate(profiles_t):
            if left.size == 0 or right.size == 0:
                out[i, j] = 1.0 if left.size == right.size else 0.0
                continue
            n = max(left.size, right.size)
            lp, rp = np.zeros(n), np.zeros(n)
            lp[: left.size], rp[: right.size] = left, right
            out[i, j] = 1.0 - np.linalg.norm(lp - rp) / math.sqrt(2.0)
    return np.clip(out, 0.0, 1.0)


def shared_hmac_sets(columns: list[list[str]]) -> list[set[bytes]]:
    """Deterministic shared-key tokens; digests remain in memory only."""
    return [
        {
            hmac.new(EXPERIMENT_HMAC_KEY, value.encode("utf-8"), hashlib.sha256).digest()
            for value in column
        }
        for column in non_null_plain_columns(columns)
    ]


def psi_containment_scores(source: list[set], target: list[set]) -> np.ndarray:
    """Symmetric containment using only |A|, |B| and PSI intersection cardinality."""
    scores = np.zeros((len(source), len(target)))
    for i, left in enumerate(source):
        for j, right in enumerate(target):
            denominator = max(len(left), len(right))
            scores[i, j] = len(left & right) / denominator if denominator else 0.0
    return scores


def shared_hmac_genuine_scores(source: list[set], target: list[set]) -> np.ndarray:
    """Cross-side gcov weighted by binomial significance in both directions."""
    all_sets = source + target
    background_size = len(set().union(*all_sets)) if all_sets else 0
    scores = np.zeros((len(source), len(target)))
    if background_size == 0:
        return scores
    for i, left in enumerate(source):
        for j, right in enumerate(target):
            shared = len(left & right)
            if not left or not right or shared == 0:
                continue
            cov_lr, cov_rl = shared / len(left), shared / len(right)
            b_lr = min(len(right) / background_size, 1.0)
            b_rl = min(len(left) / background_size, 1.0)
            g_lr = (cov_lr - b_lr) / (1.0 - b_lr) if b_lr < 1.0 else 0.0
            g_rl = (cov_rl - b_rl) / (1.0 - b_rl) if b_rl < 1.0 else 0.0
            sig = min(
                binomial_significance(len(left), shared, b_lr),
                binomial_significance(len(right), shared, b_rl),
            )
            confidence = 1.0 - math.exp(-sig)
            scores[i, j] = max(0.0, min(g_lr, g_rl)) * confidence
    return scores


def bottom_k_sketches(columns: list[set[bytes]], k: int) -> list[tuple[set[int], int]]:
    sketches = []
    for column in columns:
        hashes = sorted(int.from_bytes(value[:8], "big") for value in column)
        sketches.append((set(hashes[:k]), len(hashes)))
    return sketches


def bottom_k_containment_scores(
    source: list[tuple[set[int], int]], target: list[tuple[set[int], int]], k: int
) -> np.ndarray:
    """Estimate containment from a mergeable bottom-k Jaccard sketch plus cardinality."""
    scores = np.zeros((len(source), len(target)))
    for i, (left, n_left) in enumerate(source):
        for j, (right, n_right) in enumerate(target):
            union_bottom = set(sorted(left | right)[:k])
            if not union_bottom or max(n_left, n_right) == 0:
                continue
            jaccard = sum(value in left and value in right for value in union_bottom) / len(union_bottom)
            intersection = jaccard * (n_left + n_right) / (1.0 + jaccard)
            scores[i, j] = min(1.0, intersection / max(n_left, n_right))
    return scores


def local_name(qualified: str) -> str:
    return qualified.strip().rsplit(".", 1)[-1]


def read_uccs(path: Path) -> set[frozenset[str]]:
    keys: set[frozenset[str]] = set()
    if path.is_file():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            value = line.strip()
            if value.startswith("[") and value.endswith("]"):
                members = [x.strip() for x in value[1:-1].split(",") if x.strip()]
                if members:
                    keys.add(frozenset(local_name(member) for member in members))
    return keys


def unary_ucc_flags(keys: set[frozenset[str]], headers: list[str]) -> np.ndarray:
    unary = {next(iter(key)) for key in keys if len(key) == 1}
    return np.asarray([1.0 if header in unary else 0.0 for header in headers])


def fuse(base: np.ndarray, evidence: np.ndarray, weight: float) -> np.ndarray:
    """Fuse evidence while treating a constant matrix as no information.

    Adding a constant is mathematically assignment-invariant but can change a
    floating-point solver's arbitrary resolution of exact ties.  Returning the
    base verbatim prevents such numerical changes from being misreported as a
    feature gain.
    """
    if float(np.ptp(evidence)) <= 1e-12:
        return base.copy()
    return (1.0 - weight) * base + weight * evidence


def scale_aligned_fuse(base: np.ndarray, evidence: np.ndarray, weight: float) -> np.ndarray:
    """Affine-align new evidence to an existing matcher's score range."""
    evidence_range = float(np.ptp(evidence))
    base_range = float(np.ptp(base))
    if evidence_range <= 1e-12 or base_range <= 1e-12:
        return base.copy()
    aligned = (evidence - float(evidence.min())) / evidence_range
    aligned = aligned * base_range + float(base.min())
    return (1.0 - weight) * base + weight * aligned


def non_null_plain_columns(columns: list[list[str]]) -> list[list[str]]:
    return [
        [value.strip() for value in column if value.strip().lower() not in NULLS]
        for column in columns
    ]
