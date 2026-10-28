"""Vendored from harness/smx/afd_measures.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
from collections import OrderedDict

import numpy as np
from scipy.special import gammaln

from .relational_roles import MI_EPS, _codes, _entropy, aligned_values


MEASURES = ("gpdep", "tau", "mu_plus", "g3n", "rfi_plus")


RFI_CELL_CAP = 250_000


_LOG_FACTORIAL: "OrderedDict[int, np.ndarray]" = OrderedDict()


def _log_factorial(n):
    """log k! for k <= n, cached: n is the pair support and repeats a lot."""
    if n not in _LOG_FACTORIAL:
        if len(_LOG_FACTORIAL) >= 4:
            _LOG_FACTORIAL.popitem(last=False)
        _LOG_FACTORIAL[n] = gammaln(np.arange(n + 1) + 1.0)
    _LOG_FACTORIAL.move_to_end(n)
    return _LOG_FACTORIAL[n]


def expected_mutual_information(a, b, n):
    """Exact E[I] in nats under the both-margins-fixed permutation null.

    Vinh et al.'s closed form, regrouped: the cell term depends only on
    (a_i, b_j, n), so distinct marginal COUNT VALUES with multiplicities give
    the identical sum over (#distinct a-counts) x (#distinct b-counts) cells
    instead of len(a) x len(b). Algebra, not approximation or subsampling;
    `tests/test_afd_measures.py` checks it against the ungrouped reference in
    `rfi_cost_probe.py`.
    """
    gl = _log_factorial(n)
    log_n = np.log(n)
    ua, ma = np.unique(np.asarray(a, dtype=np.int64), return_counts=True)
    ub, mb = np.unique(np.asarray(b, dtype=np.int64), return_counts=True)
    total = 0.0
    for ai, wa in zip(ua, ma):
        for bj, wb in zip(ub, mb):
            lo, hi = max(1, int(ai) + int(bj) - n), min(int(ai), int(bj))
            if hi < lo:
                continue
            nij = np.arange(lo, hi + 1, dtype=np.int64)
            term = (nij / n) * (np.log(nij) + log_n - np.log(ai) - np.log(bj))
            logw = (gl[ai] + gl[bj] + gl[n - ai] + gl[n - bj]
                    - gl[n] - gl[nij] - gl[ai - nij] - gl[bj - nij]
                    - gl[n - ai - bj + nij])
            total += float(wa) * float(wb) * float(np.dot(term, np.exp(logw)))
    return total


def rfi_cells(a, b):
    """Grouped E[I] cell count, for the declared applicability valve."""
    return len(np.unique(a)) * len(np.unique(b))


def _positive_ratio(numerator, denominator, degenerate):
    """max(num/den, 0) under the one epsilon rule used by every measure here.

    A numerator cancelling within MI_EPS is zero, not a ratio; a denominator
    vanishing within MI_EPS is zero, not an unbounded score. Both are counted.
    """
    if abs(numerator) <= MI_EPS:
        return 0.0
    if denominator <= MI_EPS:
        degenerate[0] += 1
        return 0.0
    value = numerator / denominator
    return float(value) if value > MI_EPS else 0.0


def afd_matrices(columns, with_rfi=True):
    """Directional matrix[i, j] = score for X_i -> Y_j, plus per-pair covariates.

    Support, null policy and uninformative cases are exactly the pilot's:
    n < 2, either endpoint constant and self-edges give zero to every measure.
    """
    aligned = aligned_values(columns)
    coded = [_codes(c) for c in aligned]
    width = len(columns)
    matrices = {name: np.zeros((width, width)) for name in MEASURES}
    support = np.zeros((width, width), dtype=np.int64)
    distinct = np.zeros((width, width), dtype=np.int64)
    exact_fd = np.zeros((width, width))
    nonkey = np.zeros((width, width), dtype=bool)
    rhs_norm_entropy = np.zeros((width, width))
    rhs_collision = np.zeros((width, width))
    rfi_defined = np.zeros((width, width), dtype=bool)
    degenerate = [0]
    counters = {"pairs_evaluated": 0, "rfi_over_cap": 0, "rfi_cells_max": 0, "key_determinant": 0}
    for i, (x, _) in enumerate(coded):
        support[i, i] = int((x >= 0).sum())
        distinct[i, i] = len(set(x[x >= 0].tolist()))
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
            counters["pairs_evaluated"] += 2
            keys, counts = np.unique(a * ny_total + b, return_counts=True)
            ax, by = keys // ny_total, keys % ny_total
            mi = max(0.0, hx + hy - _entropy(counts))
            if mi <= MI_EPS:
                mi = 0.0
            nonkey[i, j] = nonkey[j, i] = nx < n and ny < n
            exact_fd[i, j] = len(counts) == nx
            exact_fd[j, i] = len(counts) == ny
            emi = None
            if with_rfi:
                pa, pb = cx[cx > 0], cy[cy > 0]
                cells = rfi_cells(pa, pb)
                counters["rfi_cells_max"] = max(counters["rfi_cells_max"], cells)
                if cells > RFI_CELL_CAP:
                    counters["rfi_over_cap"] += 2
                else:
                    emi = expected_mutual_information(pa, pb, n)
            for (p, q, det_count, ref_counts, group_counts, group_index, group_size,
                 h_target, card_target) in (
                    (i, j, nx, cy, cx[ax], ax, len(cx), hy, ny),
                    (j, i, ny, cx, cy[by], by, len(cy), hx, nx)):
                pdep = float(np.sum(counts.astype(float) ** 2 / group_counts) / n)
                collision = float(np.sum((ref_counts / n) ** 2))
                expected = collision + (det_count - 1) / (n - 1) * (1 - collision)
                rhs_collision[p, q] = collision
                rhs_norm_entropy[p, q] = h_target / np.log(card_target)

                # A key determinant exhausts both permutation nulls exactly:
                # E[pdep] = 1 and, provably, E[I] = H(Y). So mu and RFI' are
                # 0/0 there and are recorded as no evidence -- structurally,
                # from K == n, not from a float threshold. Without this the
                # RFI' ratio is two cancellation residues (~4e-12 at n=2000)
                # divided by each other, which lands arbitrarily near 1 or 0.
                # gpdep already reaches 0 here through the same cancellation.
                key_determinant = det_count == n
                counters["key_determinant"] += int(key_determinant)

                signed = pdep - expected
                if abs(signed) <= MI_EPS:
                    signed = 0.0
                matrices["gpdep"][p, q] = signed if signed > MI_EPS else 0.0
                matrices["tau"][p, q] = _positive_ratio(pdep - collision, 1 - collision, degenerate)
                matrices["mu_plus"][p, q] = (0.0 if key_determinant else
                                             _positive_ratio(signed, 1 - expected, degenerate))

                # g3n: modal-rule counterpart of tau, = Goodman-Kruskal lambda.
                # sum_x max_y n_xy over the joint cells grouped by determinant.
                group_max = np.zeros(group_size)
                np.maximum.at(group_max, group_index, counts)
                conform = float(group_max.sum()) / n
                modal = float(ref_counts.max()) / n
                matrices["g3n"][p, q] = _positive_ratio(conform - modal, 1 - modal, degenerate)

                if emi is not None:
                    rfi_defined[p, q] = not key_determinant
                    matrices["rfi_plus"][p, q] = (0.0 if key_determinant else
                                                  _positive_ratio(mi - emi, h_target - emi, degenerate))
    counters["degenerate_denominators"] = degenerate[0]
    return matrices, {"support": support, "distinct": distinct, "exact_fd": exact_fd,
                      "nonkey": nonkey, "rhs_norm_entropy": rhs_norm_entropy,
                      "rhs_collision": rhs_collision, "rfi_defined": rfi_defined}, counters
