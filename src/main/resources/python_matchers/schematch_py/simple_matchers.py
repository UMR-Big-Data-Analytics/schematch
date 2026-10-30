"""Simple table-pair matchers (labels and values), added 2026-10-15. No model, no download.

Labels are normalised as Valentine does (camelCase / snake_case / digits split, lower-cased); the table prefix of a qualified
label ("table.csv.col") is dropped. Every function returns a |S| x |T| matrix in [0, 1].

Labels:   ratcliff_obershelp, token_sort_ratio, token_set_ratio, partial_ratio, editex, bag_distance, tversky (alpha = beta =
          0.5 by default), overlap_coefficient, generalized_jaccard (Jaro-Winkler token similarity >= 0.8), abbreviation (X102's
          prefix-stripping, ordered token-prefix and consonant-skeleton measure).
Values:   tfidf_values (each column's values as one document, word tokens, cosine), range_overlap (IoU of the numeric or date
          [min, max] interval), value_length (1 - total variation distance of value-length histograms), histogram_intersection
          and bhattacharyya (numeric values on a shared 20-bin grid), energy_distance (exp(-d / pooled std)), topk_values
          (Jaccard of the 10 most frequent values), benford (1 - Jensen-Shannon distance of leading-digit distributions),
          digit_count (the same for the number of digits).
Records:  dumas (Bilke & Naumann, ICDE 2005): the k most similar record pairs across the two tables (TF-IDF cosine of whole
          rows), then the mean field-value similarity over those pairs.
Numeric-only measures return 0 for pairs where either column is not numeric (>= 90% of its non-empty values parse).
"""
from __future__ import annotations

import difflib
import math
import re
from collections import Counter
from datetime import datetime

import numpy as np

NULLS = {"", "null", "none", "nan", "na", "n/a", "-", "\\n"}


# ---------------------------------------------------------------------------------------------------------------- labels

def column_part(label: str) -> str:
    return label.split(".csv.", 1)[-1]


def split_tokens(name: str) -> list[str]:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", s)
    s = re.sub(r"([A-Za-z])([0-9])", r"\1 \2", s)
    return [t for t in re.split(r"[^A-Za-z0-9]+", s.lower()) if t]


def norm(label: str) -> str:
    return " ".join(split_tokens(column_part(label)))


def _pairwise(s_labels, t_labels, f):
    a, b = [norm(x) for x in s_labels], [norm(x) for x in t_labels]
    return np.array([[f(x, y) for y in b] for x in a], dtype=float)


def ratcliff_obershelp(s, t):
    return _pairwise(s, t, lambda x, y: difflib.SequenceMatcher(None, x, y).ratio())


def _rapidfuzz(kind):
    from rapidfuzz import fuzz
    f = {"token_sort_ratio": fuzz.token_sort_ratio, "token_set_ratio": fuzz.token_set_ratio, "partial_ratio": fuzz.partial_ratio}[kind]
    return lambda s, t: _pairwise(s, t, lambda x, y: f(x, y) / 100.0)


token_sort_ratio = _rapidfuzz("token_sort_ratio")
token_set_ratio = _rapidfuzz("token_set_ratio")
partial_ratio = _rapidfuzz("partial_ratio")

# Editex (Zobel & Dart 1996): letter groups and costs as in py_stringmatching (match 0, same group 1, otherwise 2; h and w
# are free after any letter only within the same group rule).
_EDITEX_GROUPS = ["aeiouy", "bp", "ckq", "dt", "lr", "mn", "gj", "fpv", "sxz", "csz"]


def _r(a, b):
    if a == b:
        return 0
    if any(a in g and b in g for g in _EDITEX_GROUPS):
        return 1
    return 2


def _d(a, b):
    if a != b and a in "hw":
        return 1
    return _r(a, b)


def editex_distance(x: str, y: str) -> float:
    x, y = " " + x.replace(" ", ""), " " + y.replace(" ", "")
    m, n = len(x), len(y)
    D = np.zeros((m, n))
    for i in range(1, m):
        D[i, 0] = D[i - 1, 0] + _d(x[i - 1], x[i])
    for j in range(1, n):
        D[0, j] = D[0, j - 1] + _d(y[j - 1], y[j])
    for i in range(1, m):
        for j in range(1, n):
            D[i, j] = min(D[i - 1, j] + _d(x[i - 1], x[i]), D[i, j - 1] + _d(y[j - 1], y[j]), D[i - 1, j - 1] + _r(x[i], y[j]))
    return D[m - 1, n - 1]


def editex(s, t):
    def f(x, y):
        L = max(len(x.replace(" ", "")), len(y.replace(" ", "")))
        return 1.0 - editex_distance(x, y) / (2.0 * L) if L else 1.0
    return _pairwise(s, t, f)


def bag_distance(s, t):
    def f(x, y):
        a, b = Counter(x.replace(" ", "")), Counter(y.replace(" ", ""))
        L = max(sum(a.values()), sum(b.values()))
        return 1.0 - max(sum((a - b).values()), sum((b - a).values())) / L if L else 1.0
    return _pairwise(s, t, f)


def _token_sets(s, t):
    return [set(norm(x).split()) for x in s], [set(norm(y).split()) for y in t]


def tversky(s, t, alpha=0.5, beta=0.5):
    A, B = _token_sets(s, t)
    out = np.zeros((len(A), len(B)))
    for i, a in enumerate(A):
        for j, b in enumerate(B):
            inter = len(a & b)
            den = inter + alpha * len(a - b) + beta * len(b - a)
            out[i, j] = inter / den if den else 0.0
    return out


def overlap_coefficient(s, t):
    A, B = _token_sets(s, t)
    return np.array([[len(a & b) / min(len(a), len(b)) if a and b else 0.0 for b in B] for a in A])


def generalized_jaccard(s, t, threshold=0.8):
    """Generalized Jaccard: greedy one-to-one matching of tokens with Jaro-Winkler >= threshold, matched weight over
    |A| + |B| - |matched|."""
    import jellyfish
    A, B = [norm(x).split() for x in s], [norm(y).split() for y in t]
    out = np.zeros((len(A), len(B)))
    for i, a in enumerate(A):
        for j, b in enumerate(B):
            if not a or not b:
                continue
            cand = sorted(((jellyfish.jaro_winkler_similarity(u, v), p, q) for p, u in enumerate(a) for q, v in enumerate(b)), reverse=True)
            used_a, used_b, w, k = set(), set(), 0.0, 0
            for sim, p, q in cand:
                if sim < threshold:
                    break
                if p in used_a or q in used_b:
                    continue
                used_a.add(p); used_b.add(q); w += sim; k += 1
            out[i, j] = w / (len(a) + len(b) - k)
    return out


# X102's abbreviation-aware name measure (layer3b/x102_siblings.py n_sub), unchanged in logic.
_VOWELS = set("aeiouy")


def _strip_prefix(names):
    firsts = [t[0] for t in names if len(t) > 1]
    if not firsts:
        return names
    top, cnt = Counter(firsts).most_common(1)[0]
    if cnt < max(3, 0.5 * len(names)):
        return names
    return [t[1:] if len(t) > 1 and t[0] == top else t for t in names]


def _lcs(a, b):
    if not a or not b:
        return 0
    prev = [0] * (len(b) + 1)
    for ca in a:
        cur = [0]
        for j, cb in enumerate(b):
            cur.append(prev[j] + 1 if ca == cb else max(prev[j + 1], cur[j]))
        prev = cur
    return prev[-1]


def _cover(a, b):
    if not a or not b:
        return 0.0
    s, l = (a, b) if len(a) <= len(b) else (b, a)
    return _lcs(s, l) / len(s) * (len(s) / len(l)) ** 0.5


def _skel(s):
    k = "".join(c for c in s if c not in _VOWELS)
    return k or s


def _subseq(a, b):
    it = iter(b)
    return all(c in it for c in a)


def _tok_align(ta, tb):
    if not ta or not tb:
        return 0.0
    s, l = (ta, tb) if len(ta) <= len(tb) else (tb, ta)
    pos, hit = 0, 0
    for t in s:
        for k in range(pos, len(l)):
            u = l[k]
            if u.startswith(t) or t.startswith(u) or (t[0] == u[0] and _subseq(_skel(t), u)) or (t[0] == u[0] and _subseq(_skel(u), t)):
                hit, pos = hit + 1, k + 1
                break
    return hit / max(len(ta), len(tb))


def abbreviation(s, t):
    ts = _strip_prefix([split_tokens(column_part(x)) for x in s])
    tt = _strip_prefix([split_tokens(column_part(y)) for y in t])
    M = np.zeros((len(ts), len(tt)))
    for i, a in enumerate(ts):
        ra, sa = "".join(a), _skel("".join(a))
        for j, b in enumerate(tt):
            rb, sb = "".join(b), _skel("".join(b))
            M[i, j] = 1.0 if ra == rb and ra else max(_tok_align(a, b), _cover(ra, rb), _cover(sa, sb))
    return M


# ---------------------------------------------------------------------------------------------------------------- values

def nonnull(col):
    return [v.strip() for v in col if v is not None and v.strip().lower() not in NULLS]


def numbers(col):
    vals = nonnull(col)
    out = []
    for v in vals:
        try:
            out.append(float(v.replace(",", "")))
        except ValueError:
            pass
    return np.array(out) if vals and len(out) >= 0.9 * len(vals) else None


_DATE_FORMATS = ("%Y-%m-%d", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%m/%d/%Y", "%d/%m/%Y", "%d.%m.%Y", "%Y/%m/%d")


def dates(col):
    vals = nonnull(col)[:2000]
    if not vals:
        return None
    for f in _DATE_FORMATS:
        out = []
        for v in vals:
            try:
                out.append(datetime.strptime(v, f).toordinal())
            except ValueError:
                pass
        if len(out) >= 0.9 * len(vals):
            return np.array(out, dtype=float)
    return None


def _doc(col):
    return " ".join(nonnull(col))


def tfidf_values(s_cols, t_cols):
    from sklearn.feature_extraction.text import TfidfVectorizer
    docs = [_doc(c) for c in s_cols] + [_doc(c) for c in t_cols]
    if not any(d.strip() for d in docs):
        return np.zeros((len(s_cols), len(t_cols)))
    X = TfidfVectorizer(token_pattern=r"(?u)\b\w+\b", lowercase=True).fit_transform(docs)
    S, T = X[: len(s_cols)], X[len(s_cols):]
    return np.asarray((S @ T.T).todense()).clip(0, 1)


def _interval(col):
    x = numbers(col)
    if x is None:
        x = dates(col)
    return None if x is None or len(x) == 0 else (float(x.min()), float(x.max()), x)


def range_overlap(s_cols, t_cols):
    A, B = [_interval(c) for c in s_cols], [_interval(c) for c in t_cols]
    out = np.zeros((len(A), len(B)))
    for i, a in enumerate(A):
        for j, b in enumerate(B):
            if a is None or b is None:
                continue
            lo, hi = max(a[0], b[0]), min(a[1], b[1])
            span = max(a[1], b[1]) - min(a[0], b[0])
            out[i, j] = 1.0 if span == 0 else max(hi - lo, 0.0) / span
    return out


def _length_hist(col, bins=51):
    h = np.zeros(bins)
    for v in nonnull(col):
        h[min(len(v), bins - 1)] += 1
    return h / h.sum() if h.sum() else None


def value_length(s_cols, t_cols):
    A, B = [_length_hist(c) for c in s_cols], [_length_hist(c) for c in t_cols]
    return np.array([[1.0 - 0.5 * np.abs(a - b).sum() if a is not None and b is not None else 0.0 for b in B] for a in A])


def _numeric_pair(s_cols, t_cols, f):
    A, B = [numbers(c) for c in s_cols], [numbers(c) for c in t_cols]
    out = np.zeros((len(A), len(B)))
    for i, a in enumerate(A):
        for j, b in enumerate(B):
            if a is not None and b is not None and len(a) and len(b):
                out[i, j] = f(a, b)
    return out


def _shared_hist(a, b, bins=20):
    lo, hi = min(a.min(), b.min()), max(a.max(), b.max())
    if hi == lo:
        return np.array([1.0]), np.array([1.0])
    ha, _ = np.histogram(a, bins=bins, range=(lo, hi))
    hb, _ = np.histogram(b, bins=bins, range=(lo, hi))
    return ha / ha.sum(), hb / hb.sum()


def histogram_intersection(s_cols, t_cols):
    return _numeric_pair(s_cols, t_cols, lambda a, b: float(np.minimum(*_shared_hist(a, b)).sum()))


def bhattacharyya(s_cols, t_cols):
    return _numeric_pair(s_cols, t_cols, lambda a, b: float(np.sqrt(np.prod(_shared_hist(a, b), axis=0)).sum()))


def energy_distance(s_cols, t_cols):
    from scipy.stats import energy_distance as ed

    def f(a, b):
        scale = np.std(np.concatenate([a, b]))
        return 1.0 if scale == 0 else float(math.exp(-ed(a[:5000], b[:5000]) / scale))
    return _numeric_pair(s_cols, t_cols, f)


def topk_values(s_cols, t_cols, k=10):
    A = [{v for v, _ in Counter(nonnull(c)).most_common(k)} for c in s_cols]
    B = [{v for v, _ in Counter(nonnull(c)).most_common(k)} for c in t_cols]
    return np.array([[len(a & b) / len(a | b) if a and b else 0.0 for b in B] for a in A])


def _jsd_sim(p, q):
    if p is None or q is None:
        return 0.0
    m = 0.5 * (p + q)
    kl = lambda x, y: float(np.sum(np.where(x > 0, x * np.log2(np.where(x > 0, x, 1) / np.where(y > 0, y, 1)), 0)))  # noqa: E731
    return 1.0 - math.sqrt(max(0.5 * kl(p, m) + 0.5 * kl(q, m), 0.0))


def _leading_digit(col):
    x = numbers(col)
    if x is None:
        return None
    d = [int(str(abs(v)).lstrip("0.")[:1]) for v in x if v != 0 and str(abs(v)).lstrip("0.")[:1].isdigit()]
    if not d:
        return None
    h = np.bincount(d, minlength=10)[1:10].astype(float)
    return h / h.sum() if h.sum() else None


def benford(s_cols, t_cols):
    A, B = [_leading_digit(c) for c in s_cols], [_leading_digit(c) for c in t_cols]
    return np.array([[_jsd_sim(a, b) for b in B] for a in A])


def _digit_counts(col, cap=20):
    vals = nonnull(col)
    if not vals:
        return None
    h = np.bincount([min(sum(ch.isdigit() for ch in v), cap) for v in vals], minlength=cap + 1).astype(float)
    return h / h.sum()


def digit_count(s_cols, t_cols):
    A, B = [_digit_counts(c) for c in s_cols], [_digit_counts(c) for c in t_cols]
    return np.array([[_jsd_sim(a, b) for b in B] for a in A])


# ---------------------------------------------------------------------------------------------------------------- DUMAS

def dumas(s_cols, t_cols, k=20, max_rows=2000):
    """Duplicate-based matching (Bilke & Naumann 2005), simplified: rows as TF-IDF documents, the k most similar row
    pairs (each row used at most once), then per column pair the mean of normalised Levenshtein similarity over those pairs."""
    from rapidfuzz.distance import Levenshtein
    from sklearn.feature_extraction.text import TfidfVectorizer
    ns = min(min((len(c) for c in s_cols), default=0), max_rows)
    nt = min(min((len(c) for c in t_cols), default=0), max_rows)
    out = np.zeros((len(s_cols), len(t_cols)))
    if ns == 0 or nt == 0:
        return out
    srows = [" ".join(c[r] for c in s_cols) for r in range(ns)]
    trows = [" ".join(c[r] for c in t_cols) for r in range(nt)]
    try:
        X = TfidfVectorizer(token_pattern=r"(?u)\b\w+\b").fit_transform(srows + trows)
    except ValueError:
        return out
    C = np.asarray((X[:ns] @ X[ns:].T).todense())
    pairs, used_s, used_t = [], set(), set()
    for flat in np.argsort(-C, axis=None):
        a, b = divmod(int(flat), nt)
        if C[a, b] <= 0 or len(pairs) >= k:
            break
        if a in used_s or b in used_t:
            continue
        used_s.add(a); used_t.add(b); pairs.append((a, b))
    if not pairs:
        return out
    for i, sc in enumerate(s_cols):
        for j, tc in enumerate(t_cols):
            out[i, j] = np.mean([Levenshtein.normalized_similarity(sc[a].strip().lower(), tc[b].strip().lower()) for a, b in pairs])
    return out


LABEL_METHODS = {"ratcliff_obershelp": ratcliff_obershelp, "token_sort_ratio": token_sort_ratio, "token_set_ratio": token_set_ratio,
                 "partial_ratio": partial_ratio, "editex": editex, "bag_distance": bag_distance, "tversky": tversky,
                 "overlap_coefficient": overlap_coefficient, "generalized_jaccard": generalized_jaccard, "abbreviation": abbreviation}
VALUE_METHODS = {"tfidf_values": tfidf_values, "range_overlap": range_overlap, "value_length": value_length,
                 "histogram_intersection": histogram_intersection, "bhattacharyya": bhattacharyya, "energy_distance": energy_distance,
                 "topk_values": topk_values, "benford": benford, "digit_count": digit_count, "dumas": dumas}
