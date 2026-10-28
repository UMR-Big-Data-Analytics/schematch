# Standalone snapshot of this project's paper evaluation functions.
# Sources: harness/smx/core.py and sf-matcher-study/tuning/score_screen_metrics.py
# No imports from the research workspace. Update only with reference parity tests.
# Source SHA256: core=925ba3630e61d53eb81baff5d4b418d0f5bf9a23cbda693a4ae4419e26e712f0, study=8357ad2f149ce100c943177e2026e7f7607e46d2509fcfc205e761939473a01c
"""Score-matrix diagnostics, correspondence extraction, and match metrics.

The harness deliberately separates two stages that matchers usually fuse:

    schema pair --> S (score matrix) --> M (discrete correspondences)

Stage-1 (scoring) quality is measured selection-free: rank of the ground
truth, MRR, Hits@k, margin, tie mass, flatness.  Stage-2 (selection) quality
is measured by applying a *common* set of extractors to S and reporting
|M| alongside precision/recall/F1/Overall.

That split is what lets us tell "the dependencies corrupted the similarity
signal" apart from "the similarity signal is fine and the extraction policy
turned ties into false positives".
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence

import numpy as np
from scipy.optimize import linear_sum_assignment

# Absolute tolerance used when deciding whether two scores are "the same".
# Matters a great deal: with t_rel = 1.0 an exact tie at the row maximum is
# the difference between a 1:1 matching and a many-to-many one.
DEFAULT_EPS = 1e-9


# --------------------------------------------------------------------------
# container
# --------------------------------------------------------------------------


@dataclass
class ScoreMatrix:
    """A matcher's raw output plus the ground truth it is scored against."""

    scores: np.ndarray  # (n_source, n_target) float
    ground_truth: Optional[np.ndarray] = None  # (n_source, n_target) bool
    source_labels: Optional[Sequence[str]] = None
    target_labels: Optional[Sequence[str]] = None
    meta: Dict[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.scores = np.asarray(self.scores, dtype=float)
        if self.scores.ndim != 2:
            raise ValueError(f"scores must be 2-D, got shape {self.scores.shape}")
        if self.ground_truth is not None:
            gt = np.asarray(self.ground_truth)
            if gt.shape != self.scores.shape:
                raise ValueError(
                    f"ground truth shape {gt.shape} != scores shape {self.scores.shape}"
                )
            self.ground_truth = gt.astype(bool)

    @property
    def shape(self):
        return self.scores.shape

    @property
    def n_ground_truth(self) -> int:
        return 0 if self.ground_truth is None else int(self.ground_truth.sum())


# --------------------------------------------------------------------------
# stage 1: selection-free scoring diagnostics
# --------------------------------------------------------------------------


def _row_normalised_entropy(row: np.ndarray) -> float:
    """Flatness of one source row in [0, 1]; 1.0 == no discrimination at all."""
    n = row.size
    if n <= 1:
        return 0.0
    p = np.clip(row, 0.0, None)
    total = p.sum()
    if total <= 0:
        return 1.0  # an all-zero row discriminates nothing
    p = p / total
    nz = p[p > 0]
    return float(-(nz * np.log(nz)).sum() / np.log(n))


def recall_at_ground_truth(
    S: np.ndarray, gt: np.ndarray, eps: float = DEFAULT_EPS
) -> Dict[str, float]:
    """Valentine's Recall at ground-truth size, reported for both tie orders.

    Take the |GT| best-scoring cells of the whole matrix and ask how many of
    them are correct. Because k equals |GT|, precision and recall at k coincide,
    so one number describes both.

    This is the one accuracy figure that does not depend on an extraction
    policy: it scores the matrix itself. Section 5o showed why that matters --
    the same unmatched penalty made PDED emit nothing while PRISMA kept 81% of
    ground-truth cardinality, purely because their score ranges differ, so any
    comparison mediated by a selection rule partly measures the rule.

    Ties at the cut-off are resolved both ways, as elsewhere in this module. A
    flat matrix has every cell tied, so its optimistic figure can look strong
    while the pessimistic one collapses; the gap is the warning.
    """
    S = np.asarray(S, dtype=float)
    gt = np.asarray(gt, dtype=bool)
    k = int(gt.sum())
    out: Dict[str, float] = {"recall_at_gt_optimistic": float("nan"),
                             "recall_at_gt_pessimistic": float("nan")}
    if k == 0 or S.size == 0:
        return out

    flat = S.ravel()
    truth = gt.ravel()
    k = min(k, flat.size)
    # Score of the k-th best cell; everything strictly above it is certain to
    # be selected, everything equal to it competes for the remaining slots.
    cutoff = np.partition(flat, flat.size - k)[flat.size - k]
    above = flat > cutoff + eps
    tied = np.abs(flat - cutoff) <= eps

    hits_above = int((above & truth).sum())
    slots = k - int(above.sum())
    tied_correct = int((tied & truth).sum())
    tied_wrong = int(tied.sum()) - tied_correct

    if slots <= 0:
        # More cells clear the cut-off than there are slots, which can only
        # happen through eps widening; fall back to the certain hits.
        out["recall_at_gt_optimistic"] = hits_above / k
        out["recall_at_gt_pessimistic"] = hits_above / k
        return out

    best = hits_above + min(slots, tied_correct)
    worst = hits_above + max(0, slots - tied_wrong)
    out["recall_at_gt_optimistic"] = best / k
    out["recall_at_gt_pessimistic"] = worst / k
    return out


def scoring_diagnostics(sm: ScoreMatrix, eps: float = DEFAULT_EPS) -> Dict[str, float]:
    """Measure S without ever extracting a matching.

    Ranks are reported both optimistically (ties resolved in our favour) and
    pessimistically (ties resolved against us).  The gap between the two is
    itself the tie signal: if optimistic rank is 1 but pessimistic rank is 6,
    the correct target is tied with five others and any threshold-style
    extractor will emit all six.
    """
    S = sm.scores
    n_src, n_tgt = S.shape
    out: Dict[str, float] = {"n_source": n_src, "n_target": n_tgt}

    # --- tie / flatness structure (independent of ground truth) ---
    if n_src and n_tgt:
        row_max = S.max(axis=1, keepdims=True)
        ties_at_row_max = (np.abs(S - row_max) <= eps).sum(axis=1)
    else:
        ties_at_row_max = np.zeros(n_src, dtype=int)
    out["tie_mass_row_max"] = float(ties_at_row_max.sum())
    out["mean_ties_at_row_max"] = float(ties_at_row_max.mean()) if n_src else 0.0
    out["frac_rows_tied_at_max"] = float((ties_at_row_max > 1).mean()) if n_src else 0.0
    out["mean_row_entropy"] = (
        float(np.mean([_row_normalised_entropy(r) for r in S])) if n_src else 0.0
    )
    out["score_std"] = float(S.std()) if S.size else 0.0
    out["frac_zero_scores"] = float((np.abs(S) <= eps).mean()) if S.size else 0.0

    if sm.ground_truth is None:
        return out

    gt = sm.ground_truth
    ranks_opt: List[int] = []
    ranks_pess: List[int] = []
    margins: List[float] = []
    for i in range(n_src):
        pos = np.flatnonzero(gt[i])
        if pos.size == 0:
            continue  # source column with no correspondence: not rankable
        best = S[i, pos].max()
        ranks_opt.append(1 + int((S[i] > best + eps).sum()))
        # MRR ranks the *first* relevant result. Other relevant results tied
        # with it cannot be placed ahead as if they were false candidates.
        ranks_pess.append(1 + int(((~gt[i]) & (S[i] >= best - eps)).sum()))
        neg = S[i][~gt[i]]
        margins.append(float(best - neg.max()) if neg.size else float("inf"))

    out.update(recall_at_ground_truth(S, gt, eps=eps))

    out["n_rankable_rows"] = len(ranks_opt)
    if not ranks_opt:
        return out

    ro = np.asarray(ranks_opt, dtype=float)
    rp = np.asarray(ranks_pess, dtype=float)
    finite_margins = np.asarray([m for m in margins if np.isfinite(m)], dtype=float)

    out["mrr_optimistic"] = float((1.0 / ro).mean())
    out["mrr_pessimistic"] = float((1.0 / rp).mean())
    out["mean_rank_optimistic"] = float(ro.mean())
    out["mean_rank_pessimistic"] = float(rp.mean())
    out["hits_at_1_optimistic"] = float((ro <= 1).mean())
    out["hits_at_1_pessimistic"] = float((rp <= 1).mean())
    out["hits_at_5_pessimistic"] = float((rp <= 5).mean())
    out["mean_margin"] = (
        float(finite_margins.mean()) if finite_margins.size else float("nan")
    )
    out["frac_positive_margin"] = (
        float((finite_margins > 0).mean()) if finite_margins.size else float("nan")
    )
    return out


# --------------------------------------------------------------------------
# stage 2: extraction policies
# --------------------------------------------------------------------------


def _safe_ratio(num: np.ndarray, den: np.ndarray) -> np.ndarray:
    out = np.zeros_like(num, dtype=float)
    np.divide(num, den, out=out, where=(den > 0))
    return out


def top1_per_source(S: np.ndarray, eps: float = DEFAULT_EPS, **_) -> np.ndarray:
    """Every row maximum (ties included). Recall-oriented, unbounded |M|."""
    if S.size == 0:
        return np.zeros(S.shape, dtype=bool)
    row_max = S.max(axis=1, keepdims=True)
    return (np.abs(S - row_max) <= eps) & (row_max > eps)


def mutual_top1(S: np.ndarray, eps: float = DEFAULT_EPS, **_) -> np.ndarray:
    """Row maximum AND column maximum, ties included.

    Identical to rel_threshold at t_rel = 1.0.  Without ties this is a
    partial 1:1 matching; with ties it is not, which is exactly the
    mechanism we are trying to expose.
    """
    if S.size == 0:
        return np.zeros(S.shape, dtype=bool)
    row_max = S.max(axis=1, keepdims=True)
    col_max = S.max(axis=0, keepdims=True)
    return (np.abs(S - row_max) <= eps) & (np.abs(S - col_max) <= eps) & (S > eps)


def rel_threshold(
    S: np.ndarray, t_rel: float = 1.0, eps: float = DEFAULT_EPS, **_
) -> np.ndarray:
    """Melnik's SelectThreshold, as used by the thesis (t_rel = 1.0).

    A pair holds iff its similarity relative to the best partner of *both*
    endpoints is at least t_rel.
    """
    if S.size == 0:
        return np.zeros(S.shape, dtype=bool)
    row_max = np.broadcast_to(S.max(axis=1, keepdims=True), S.shape)
    col_max = np.broadcast_to(S.max(axis=0, keepdims=True), S.shape)
    fwd = _safe_ratio(S, row_max)
    bwd = _safe_ratio(S, col_max)
    return (fwd >= t_rel - eps) & (bwd >= t_rel - eps) & (S > eps)


def global_threshold(S: np.ndarray, t: float = 0.5, **_) -> np.ndarray:
    """Absolute cut on the raw score."""
    return S >= t


def hungarian(
    S: np.ndarray, drop_nonpositive: bool = True, eps: float = DEFAULT_EPS, **_
) -> np.ndarray:
    """Optimal one-to-one assignment maximising total score.

    Bounded by construction at min(n_source, n_target) pairs, so it cannot
    exhibit tie inflation.  Included so that every matcher can be scored
    under a selection policy that is provably cardinality-controlled.
    """
    M = np.zeros(S.shape, dtype=bool)
    if S.size == 0:
        return M
    rows, cols = linear_sum_assignment(S, maximize=True)
    for r, c in zip(rows, cols):
        if drop_nonpositive and S[r, c] <= eps:
            continue
        M[r, c] = True
    return M


def partial_hungarian(
    S: np.ndarray, penalty: float = 0.5, eps: float = DEFAULT_EPS, **_
) -> np.ndarray:
    """Optimal assignment that is allowed to leave columns unmatched.

    Plain Hungarian must output min(n, m) pairs whatever the scores say. That is
    right for unionable scenarios, where every column does have a partner, and
    wrong everywhere else: given a column with no counterpart it still has to
    invent one, so precision falls as the share of unmatchable columns rises.

    The standard fix is to give every row and column its own dummy partner worth
    `penalty`. A real pair is then chosen only when it beats leaving both
    endpoints unmatched, so `penalty` is the score a correspondence has to clear
    -- but applied inside the optimisation rather than as a filter afterwards,
    which lets one weak pair be dropped in favour of a better arrangement of the
    rest.
    """
    n, m = S.shape
    if n == 0 or m == 0:
        return np.zeros(S.shape, dtype=bool)

    size = n + m
    augmented = np.zeros((size, size), dtype=float)
    augmented[:n, :m] = S
    # row i may take its own dummy column, and column j its own dummy row
    augmented[:n, m:] = -np.inf
    augmented[n:, :m] = -np.inf
    half = penalty / 2.0
    np.fill_diagonal(augmented[:n, m:m + n], half)
    np.fill_diagonal(augmented[n:n + m, :m], half)
    # dummy-to-dummy pairings are free, so the assignment always completes
    augmented[n:, m:] = 0.0
    augmented[np.isneginf(augmented)] = -1e9

    rows, cols = linear_sum_assignment(augmented, maximize=True)
    M = np.zeros(S.shape, dtype=bool)
    for r, c in zip(rows, cols):
        if r < n and c < m and S[r, c] > eps:
            M[r, c] = True
    return M


EXTRACTORS: Dict[str, Callable[..., np.ndarray]] = {
    "top1_per_source": top1_per_source,
    "mutual_top1": mutual_top1,
    "rel_threshold_1.0": lambda S, **kw: rel_threshold(S, t_rel=1.0, **kw),
    "rel_threshold_0.95": lambda S, **kw: rel_threshold(S, t_rel=0.95, **kw),
    # Schematch's BinaryMetric, and therefore the PRISMA paper's native
    # evaluation, treats every score >= 0.5 as a correspondence.
    "global_threshold_0.5": lambda S, **kw: global_threshold(S, t=0.5, **kw),
    "hungarian": hungarian,
    "partial_hungarian_0.3": lambda S, **kw: partial_hungarian(S, penalty=0.3, **kw),
    "partial_hungarian_0.5": lambda S, **kw: partial_hungarian(S, penalty=0.5, **kw),
    "partial_hungarian_0.7": lambda S, **kw: partial_hungarian(S, penalty=0.7, **kw),
}


def extract(S: np.ndarray, policy: str, **kwargs) -> np.ndarray:
    if policy not in EXTRACTORS:
        raise KeyError(f"unknown extractor {policy!r}; have {sorted(EXTRACTORS)}")
    return EXTRACTORS[policy](S, **kwargs)


# --------------------------------------------------------------------------
# stage 2: match metrics
# --------------------------------------------------------------------------


def match_metrics(M: np.ndarray, gt: np.ndarray) -> Dict[str, float]:
    """Precision/recall/F1/Overall plus the cardinality of the match set.

    |M| is reported first-class: it is the quantity that distinguishes
    "the matcher found better correspondences" from "the matcher simply
    emitted more of them".
    """
    M = np.asarray(M, dtype=bool)
    gt = np.asarray(gt, dtype=bool)
    tp = int((M & gt).sum())
    fp = int((M & ~gt).sum())
    fn = int((~M & gt).sum())

    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = (
        2 * precision * recall / (precision + recall)
        if (precision + recall) > 0
        else 0.0
    )
    # Faithful to schematch's Overall.java: zero when precision is zero.
    overall = recall * (2.0 - 1.0 / precision) if precision > 0 else 0.0

    n_match = int(M.sum())
    n_gt = int(gt.sum())
    return {
        "n_match": n_match,
        "n_ground_truth": n_gt,
        "match_ratio": (n_match / n_gt) if n_gt else float("nan"),
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "overall": overall,
    }


def evaluate(
    sm: ScoreMatrix,
    policies: Optional[Sequence[str]] = None,
    eps: float = DEFAULT_EPS,
) -> List[Dict[str, object]]:
    """Full report for one score matrix: one tidy row per extraction policy.

    Scoring diagnostics are identical across the rows by construction (they
    do not depend on the extractor); they are repeated so that the output is
    a single flat table suitable for grouping and plotting.
    """
    if sm.ground_truth is None:
        raise ValueError("evaluate() needs ground truth")
    policies = list(policies) if policies is not None else list(EXTRACTORS)
    diag = scoring_diagnostics(sm, eps=eps)

    rows: List[Dict[str, object]] = []
    for policy in policies:
        M = extract(sm.scores, policy, eps=eps)
        row: Dict[str, object] = dict(sm.meta)
        row["extractor"] = policy
        row.update(diag)
        row.update(match_metrics(M, sm.ground_truth))
        rows.append(row)
    return rows


import hashlib
K_PERM = 50

def f1(M: np.ndarray, gt: np.ndarray) -> float:
    return float(match_metrics(M, gt)["f1"])

def hungarian_random(S: np.ndarray, gt: np.ndarray, perms: list[tuple[np.ndarray, np.ndarray]],
                     eps: float = DEFAULT_EPS) -> float:
    """Mean Hungarian F1 over random row/column orders: ties no longer follow the index."""
    vals = []
    for pr, pc in perms:
        rows, cols = linear_sum_assignment(S[np.ix_(pr, pc)], maximize=True)
        M = np.zeros(S.shape, dtype=bool)
        for r, c in zip(rows, cols):
            if S[pr[r], pc[c]] > eps:   # same drop_nonpositive rule as core.hungarian
                M[pr[r], pc[c]] = True
        vals.append(f1(M, gt))
    return float(np.mean(vals))

def recall_expected(S: np.ndarray, gt: np.ndarray, eps: float = DEFAULT_EPS) -> float:
    """Recall@GT with ties at the cut-off filled at random: expected hits are hypergeometric."""
    flat, truth = S.ravel(), gt.ravel()
    k = min(int(truth.sum()), flat.size)
    if k == 0:
        return float("nan")
    cutoff = np.partition(flat, flat.size - k)[flat.size - k]
    above = flat > cutoff + eps
    tied = np.abs(flat - cutoff) <= eps
    slots = k - int(above.sum())
    hits = int((above & truth).sum())
    if slots <= 0 or not tied.any():
        return hits / k
    return (hits + slots * int((tied & truth).sum()) / int(tied.sum())) / k

def mrr_both(S: np.ndarray, gt: np.ndarray, eps: float = DEFAULT_EPS) -> tuple[float, float]:
    """MRR with ties against the ground truth, and its exact expectation under random ties."""
    pess, exp = [], []
    for i in range(S.shape[0]):
        pos = np.flatnonzero(gt[i])
        if pos.size == 0:
            continue
        best = S[i, pos].max()
        neg = ~gt[i]
        n_above = int((neg & (S[i] > best + eps)).sum())
        t_neg = int((neg & (np.abs(S[i] - best) <= eps)).sum())
        t_pos = int((gt[i] & (np.abs(S[i] - best) <= eps)).sum())
        pess.append(1.0 / (1 + n_above + t_neg))
        # position j of the first relevant item in a random order of the tied group
        t, e, p_none_yet = t_pos + t_neg, 0.0, 1.0
        for j in range(1, t_neg + 2):
            p_here = p_none_yet * t_pos / (t - (j - 1))
            e += p_here / (n_above + j)
            p_none_yet *= (t_neg - (j - 1)) / (t - (j - 1)) if j <= t_neg else 0.0
        exp.append(e)
    return (float(np.mean(pess)), float(np.mean(exp))) if pess else (float("nan"), float("nan"))

def all_metrics(S: np.ndarray, gt: np.ndarray, perms) -> dict[str, float]:
    mp, me = mrr_both(S, gt)
    return {"F1_H": f1(extract(S, "hungarian"), gt),
            "F1_Hr": hungarian_random(S, gt, perms),
            "F1_M": f1(extract(S, "mutual_top1"), gt),
            "R_pess": float(recall_at_ground_truth(S, gt)["recall_at_gt_pessimistic"]),
            "R_exp": recall_expected(S, gt),
            "MRR_pess": mp, "MRR_exp": me}

def permutations(shape: tuple[int, int], key: str) -> list[tuple[np.ndarray, np.ndarray]]:
    rng = np.random.default_rng(int(hashlib.sha256(key.encode()).hexdigest()[:16], 16))
    return [(rng.permutation(shape[0]), rng.permutation(shape[1])) for _ in range(K_PERM)]
