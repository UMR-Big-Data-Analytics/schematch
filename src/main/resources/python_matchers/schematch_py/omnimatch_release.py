"""OmniMatch, ported from the authors' release (scenario matcher, added 2026-10-15).

Provenance. Koutras et al., "OmniMatch: Joinability Discovery in Data Products"; code release amazon-science/omnimatch,
commit 515f8ce, licence CC BY-NC 4.0 (non-commercial; this port is for research use and keeps the attribution). The logic of
src/training_generator.py, featurizer.py, tools.py, omnimatch_models.py and omnimatch_predictors.py was re-implemented in
plain numpy / PyTorch (the release needs dgl 0.5.3, torch 1.7, fasttext, wordninja, unidecode); no large blocks were copied.
This is distinct from holistic.omnimatch, which is a paper-level reimplementation.

Pipeline (release defaults from README.md: model_loss rgcn_margin, graph_construction topk, k 3, dimension 256, epochs 30,
learning_rate 0.001, margin 0.5, norm 2, number_of_datasets 2, number_of_sources 20, all four features on):
 1. Self-supervision (training_generator.py). Every source and target table T of the scenario is split horizontally into
    two tables that share cc common columns (cc drawn from common_cols, picked either as the cc columns with most distinct
    values or at random) and divide the remaining columns, then vertically: both keep the first int(overlap * n) rows,
    T_0 adds the next randint(1, n - ov - 1) rows and T_1 takes the rest; with probability 1/2 the T_1 values are
    perturbed (typo_prc 10% of the characters of every string value get a QWERTY-neighbour typo, after an ASCII fold;
    float64 columns are replaced by a re-drawn normal with mean/std shifted by +-10..50%; integer columns are kept; some
    date formats are reformatted), either only on the overlapping rows or (cascade) on the whole of T_1. The generated
    tables go through a CSV round trip as in the release. Join pairs are the common columns (T_0.c, T_1.c); every other
    T_0 x T_1 column pair is a non-join. Ground truth is never read.
 2. Pair features (featurizer.py; feature order as in the config, which gives 5 relation types):
      jaccard_frequent  Jaccard of the *infrequent*-token sets (per value, the token least frequent in the column; the
                        release names it "frequent" but calls compute_jaccard_infrequent_tokens);
      value_embeddings  cosine of the mean word vector over the frequent-token sets (per value, the most frequent token);
      value_distribution 1 - Jensen-Shannon divergence (natural log) of the two *column-name* token lists (the release
                        passes self.tokens, the header tokens from tokenize_columns(split_words=True), not the value
                        tokens; js_tokens="values" switches to the value-token lists the method describes);
      jaccard, max containment  of the distinct value sets (values lower-cased, trailing ".0" removed).
    Node features (tools.get_individual_features): the 987 Sherlock-style statistics (96 characters x 10 aggregates + 27
    bag-of-words statistics) over str() of every cell (NaN -> "nan"), each feature L2-normalised over the graph's nodes.
 3. Graph (create_edge_list_topk): for every node and every feature, directed edges from its top-k candidate partners
    (cumulative stable sorts, as in the release), one relation type per feature. The training graph has the generated
    columns as nodes and the T_0 x T_1 pairs as candidates; the test graph has the scenario's source and target columns as
    nodes and all source x target pairs as candidates (row-major order).
 4. Model: two RelGraphConv layers (dgl 0.5.3 semantics: basis regulariser with num_bases = num_rels, i.e. one full weight
    per relation; sum aggregation with no edge normalisation; self-loop weight; zero-initialised bias; Xavier-uniform with
    ReLU gain; no dropout; no activation inside the layer), ReLU between them, row L2 normalisation after the second;
    full-batch Adam, triplet margin loss on (anchor, join partner, every non-join neighbour) triplets of number_of_sources
    sampled generated pairs, the first 90% of the triplets used for training (the release's split; its validation loss
    does not affect training and is not computed).
 5. Score (compute_score for rgcn_margin): d = ||z_s - z_t||_2 between the unit embeddings of the trained model applied
    inductively to the test graph; the release ranks by 1 - d, this returns 1 - d / 2 in [0, 1] (same order).

Word vectors. The release loads fastText cc.en.50.bin (cc.en.300 reduced with fasttext.util.reduce_model). Here
options["vectors"] or env SCHEMATCH_FASTTEXT names a fastText .bin (gensim load_facebook_vectors: subword vectors for OOV
tokens, as fastText's get_word_vector) or a word2vec .vec text file (OOV tokens skipped). If options["dim"] (default 50) is
below the native dimension, the vectors are projected on the top-dim principal axes of the covariance of the first
pca_rows (100000) vocabulary rows, uncentred projection, as reduce_model does (it uses np.linalg.eig, we use sorted eigh);
because a fastText vector is a mean of rows, projecting the final vector equals projecting the matrices. No vectors -> a
FileNotFoundError. The loaded/reduced model is cached with lru_cache and optionally on disk (options["vectors_cache"] or env
SCHEMATCH_FASTTEXT_CACHE, an .npz keyed by path, size, mtime, dim and pca_rows).

Deviations from the release (beyond dgl -> plain torch, which is exact up to float summation order):
 - Placeholder generator parameters: common_cols = range(1, 6) (common_cols_l 1 and step 1 are config defaults, r is a
   placeholder), overlaps 0.1..0.9 step 0.1 (placeholder), num_iterations 1, typo_prc 10, cov_prc 100 (config defaults).
 - One seeded numpy Generator replaces python random / np.random (the release also forks a process pool per column).
 - Tables whose vertical split is impossible (randint(1, n - ov - 1) with an empty range, e.g. 1-2 row tables; the release
   raises) generate no training pair; an empty overlap slice is not perturbed (the release raises); fewer generated pairs
   than number_of_sources -> all are used (the release raises).
 - unidecode -> NFKD + ASCII drop; wordninja -> the same dynamic program over a frequency-ranked word list: options
   ["wordlist"] (e.g. wordninja's wordninja_words.txt.gz, one word per line), else the first 126000 lower-case
   [a-z0-9'] entries of the vector vocabulary (cc.en is frequency-ordered).
 - scipy >= 1.9 returns NaN for skew/kurtosis of constant data; the release's scipy 1.5.4 returned 0 / -3, reproduced.
 - NaN cosines (zero mean vectors) become 0.0. Candidate pairs at test time are ordered row-major (the release lists GT
   join pairs first, which only matters for top-k tie breaking); the order of the release's training non-join pickles is
   not published, we use T_0-column-major order. Tables are read with pandas.read_csv (as the release), capped at
   max_rows (default multitable.MAXROWS, like the sibling matchers; 0 = all rows). Labels absent from the CSVs are a
   single NaN cell. With no generated pair (all tables too short) the model stays at its seeded initialisation.
 - torch.use_deterministic_algorithms is switched on while training (and restored): the CPU backward of repeated-index
   gathers is otherwise order-nondeterministic (~1e-7). Same seed -> bit-identical output, also across processes.
 - Replicated release quirks: the value_distribution header-token input (above); _get_negative_neighbors resets the list of
   the second column of each non-join pair (it tests the column tuple instead of its id against int keys); the "random"
   negative sample in get_train_val_nodes is unused (all non-join neighbours are taken in order); NaN cells of string
   columns become "nan" and receive typos.

Options: vectors, dim (50), seed (20261015), k (3), dimension (256), epochs (30), lr (0.001), margin (0.5), norm (2),
number_of_sources (20), number_of_datasets (2), num_iterations (1), common_cols ((1, 6, 1)), overlaps ((0.1, 0.9, 0.1)),
typo_prc (10), cov_prc (100), max_rows (20000), js_tokens ("header"), wordlist, vectors_cache, pca_rows (100000),
vectors_limit (None; .vec only), arm ("full"; ablation arms "no_edges", "one_step_edges", "random_forest", see ARMS;
random_forest needs scikit-learn).
"""
from __future__ import annotations

import csv
import datetime
import gzip
import io
import itertools
import math
import os
import re
import string
import unicodedata
import warnings
from collections import Counter
from functools import lru_cache
from pathlib import Path

import numpy as np
import pandas as pd

from . import multitable as x105

N_FEATURES = 5  # jaccard_frequent, value_embeddings, value_distribution, jaccard, max containment

# ------------------------------------------------------------------------------------------------- random helpers


class _Rand:
    """python-random-like calls on a seeded numpy Generator."""

    def __init__(self, seed):
        self.g = np.random.default_rng(seed)

    def choice(self, seq):
        return seq[int(self.g.integers(len(seq)))]

    def randint(self, a, b):  # inclusive, like random.randint
        if b < a:
            raise ValueError("empty range")
        return int(self.g.integers(a, b + 1))

    def sample(self, seq, k):
        seq = list(seq)
        if k > len(seq) or k < 0:
            raise ValueError("sample larger than population")
        return [seq[i] for i in self.g.choice(len(seq), size=k, replace=False)]


# ------------------------------------------------------------------------- training-data generation (generator)

# QWERTY neighbours used by the release's typo injection
_PROXIMITY = {k: list(v) for k, v in {
    "a": "qwzsx", "b": "fghvn", "c": "sxdfv", "d": "wersxcfv", "e": "wrsdf234", "f": "ertdcbgv", "g": "rtyfbhvn",
    "h": "tyubmjgn", "i": "uojkl789", "j": "yuimhkn", "k": "uiomjl", "l": "iopk", "m": "jhkn", "n": "bmjgh",
    "o": "ipkl890", "p": "ol90", "q": "was12", "r": "etdfg345", "s": "qweazxdc", "t": "ryfgh456", "u": "yijhk678",
    "v": "dcfbg", "w": "qeasd123", "x": "azsdc", "y": "tujgh567", "z": "asx", "1": "qw2", "2": "qwe13", "3": "wer24",
    "4": "ert35", "5": "rty46", "6": "tyu57", "7": "yui68", "8": "uio79", "9": "iop80", "0": "op9"}.items()}
_LETTERS = list(string.ascii_lowercase)
_DATE_FORMATS = ["%m/%d/%Y", "%m/%d/%Y %I:%M:%S %p", "%A, %B %d, %Y", "%I:%M %p", "%Y-%m-%dT%H:%M:%S.000Z"]


def _ascii(s):
    return unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode("ascii")


def _split_horizontal(df, common, pick_unique, R):
    cols = df.columns.tolist()
    common = max(common, 1)
    if pick_unique:
        ranked = sorted(cols, key=lambda c: len(set(df[c].dropna())), reverse=True)
        keep = ranked[:common]
    else:
        keep = R.sample(cols, common)
    rest = df.drop(columns=keep)
    half = len(rest.columns) // 2
    store = df[keep]
    return pd.concat([store, rest.iloc[:, :half]], axis=1), pd.concat([store, rest.iloc[:, half:]], axis=1), keep


def _reformat_date(v, fmt_id):
    a = datetime.datetime.strptime(v, _DATE_FORMATS[fmt_id])
    if fmt_id in (0, 1):
        return a.date().strftime("%A %d %B %Y")
    if fmt_id == 2:
        return a.date().strftime("%m/%d/%Y")
    if fmt_id == 3:
        return a.time().isoformat()[:5]
    return "{0} {1}".format(a.date().strftime("%m/%d/%Y"), a.time().strftime("%I:%M %p"))


def _perturb_numbers(vals, R):
    data = np.array(vals, dtype=float)
    tmp = data[~np.isnan(data)]
    if tmp.size == 0:
        return data
    sign = R.sample([-1, 1], 1)[0]
    shift = sign * R.randint(10, 50) / 100
    mu, sd = np.mean(tmp), np.std(tmp)
    new = np.absolute(R.g.normal(mu + mu * shift, sd + sd * shift, tmp.size))
    if (tmp % 1 == 0).all():
        new = np.around(new)
    out = data.copy()
    out[~np.isnan(data)] = new
    return out


def _typo(value, typo_prc, R):
    value = _ascii(str(value))
    if value.replace("-", "").isnumeric():
        return value.replace("-", " ")
    word, symbols = [], {}
    for i, ch in enumerate(value):
        if ch.isalnum():
            word.append(ch)
        else:
            symbols[i] = ch
    hml = max(int(len(word) * typo_prc / 100), 1)
    try:
        positions = R.sample(range(len(word)), hml)
    except ValueError:
        positions = []
    for p in positions:
        ch = word[p].lower()
        pick = R.choice(_PROXIMITY[ch]) if ch in _PROXIMITY else R.choice(_LETTERS)
        while word[p] == pick:
            pick = R.choice(_LETTERS)
        word[p] = pick
    out = "".join(word)
    for i, ch in symbols.items():
        out = out[:i] + ch + out[i:]
    return out


def _perturb_column(vals, dtype, n_records, typo_prc, R):
    vals = list(vals)
    if not vals:
        return vals
    fmt_id = None
    if isinstance(vals[0], str):
        for i, fmt in enumerate(_DATE_FORMATS):
            try:
                datetime.datetime.strptime(vals[0], fmt)
                fmt_id = i
                break
            except ValueError:
                continue
    if fmt_id is not None:
        for rec in R.sample(range(len(vals)), n_records):
            try:
                vals[rec] = _reformat_date(vals[rec], fmt_id)
            except (ValueError, TypeError):
                continue
        return vals
    if dtype == "float64":
        return _perturb_numbers(vals, R)
    if dtype != "object":
        return vals
    for rec in R.sample(range(len(vals)), n_records):
        vals[rec] = _typo(vals[rec], typo_prc, R)
    return vals


def _update_values(frame, cov_prc, typo_prc, cols, R):
    if len(frame) == 0:
        return frame.reset_index(drop=True)
    n_records = int(len(frame) * cov_prc / 100)
    out = {}
    for c in frame.columns:
        vals = frame[c].tolist()
        out[c] = _perturb_column(vals, str(frame[c].dtype), n_records, typo_prc, R) if (not cols or c in cols) else vals
    return pd.DataFrame(out)


def _split_vertical(s1, s2, overlap, typos, cov_prc, typo_prc, cascade, R):
    n = len(s2)
    ov = int(overlap * n)
    cut = R.randint(1, n - ov - 1)  # ValueError for tables that are too short (the release raises too)
    t1 = s1.iloc[:ov + cut]
    t2 = pd.concat([s2.iloc[:ov], s2.iloc[ov + cut:]])
    if typos:
        shared = set(t1.columns) & set(t2.columns)
        if cascade:
            t2 = _update_values(t2, cov_prc, typo_prc, shared, R)
        else:
            t2 = pd.concat([_update_values(s2.iloc[:ov], cov_prc, typo_prc, shared, R), s2.iloc[ov + cut:]])
    return t1, t2


def _csv_roundtrip(df):
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        out = pd.read_csv(io.StringIO(df.to_csv(index=False)))
    out.columns = [str(c).rstrip() for c in out.columns]
    return out


def _grid(lo, hi, step):
    vals = []
    while lo <= hi:
        vals.append(lo)
        lo += step
    return vals


def generate_pairs(tables, options, R):
    """[(source_name, iteration, T_0, T_1, common_columns)] in table order (the release's training_generator main)."""
    cl, cr, cs = (int(x) for x in options.get("common_cols", (1, 6, 1)))
    common_choices = list(range(cl, cr, cs))
    overlaps = _grid(*(float(x) for x in options.get("overlaps", (0.1, 0.9, 0.1))))
    typo_prc, cov_prc = int(options.get("typo_prc", 10)), int(options.get("cov_prc", 100))
    out = []
    for it in range(int(options.get("num_iterations", 1))):
        for name, df in tables:
            if df.shape[1] == 0:
                continue
            cc = min(R.choice(common_choices), df.shape[1])
            pick_unique = R.choice([True, False])
            g1, g2, common = _split_horizontal(df, cc, pick_unique, R)
            overlap = R.choice(overlaps)
            typos, cascade = R.choice([True, False]), R.choice([True, False])
            try:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore")
                    t1, t2 = _split_vertical(g1, g2, overlap, typos, cov_prc, typo_prc, cascade, R)
            except ValueError:
                continue
            out.append((name, it, _csv_roundtrip(t1), _csv_roundtrip(t2), [str(c).rstrip() for c in common]))
    return out


# --------------------------------------------------------------------------------- node features (tools.py)

_CHARS = [c for c in string.printable if c not in ("\n", "\\", "\v", "\r", "\t", "^")] + ["\\", "^"]
_SPEC = '[[!@#$%^&*(),.?":{}|<>]]'  # the release's pattern: one special character followed by "]"


def _moments(x):
    x = np.asarray(x, dtype=float)
    d = x - x.mean()
    return np.mean(d ** 2), np.mean(d ** 3), np.mean(d ** 4)


def _skew(x):  # scipy 1.5.4: 0 for constant data
    m2, m3, _ = _moments(x)
    return 0.0 if m2 == 0 else float(m3 / m2 ** 1.5)


def _kurtosis(x):  # scipy 1.5.4, Fisher: -3 for constant data
    m2, _, m4 = _moments(x)
    return (0.0 if m2 == 0 else float(m4 / m2 ** 2.0)) - 3.0


def _aggregates(v):
    v = np.asarray(v)
    return [int(bool(np.any(v))), int(bool(np.all(v))), float(np.mean(v)), float(np.var(v)), float(np.min(v)),
            float(np.max(v)), float(np.median(v)), float(np.sum(v)), _kurtosis(v), _skew(v)]


def _column_profile(data: pd.Series):
    """The 987 numbers of extract_bag_of_characters_features + extract_bag_of_words_features for one column (str)."""
    if len(data) == 0:
        data = pd.Series(["nan"])
    arr = np.asarray(data.tolist(), dtype=str)
    f = []
    for c in _CHARS:
        f.extend(_aggregates(np.strings.count(arr, c)))
    n = len(data)
    counts = Counter(data.tolist())
    f.append(-sum((k / n) * math.log(k / n, 2) for k in counts.values()))
    f.append(data.nunique() / n)
    f.append(int(data.str.contains("[0-9]", regex=True).sum()) / n)
    f.append(int(data.str.contains("[a-z]|[A-Z]", regex=True).sum()) / n)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        spec = data.str.count(_SPEC)
    for cnt in (data.str.count("[0-9]"), data.str.count("[a-z]|[A-Z]"), spec, data.str.count('[" "]') + 1):
        f.extend([float(np.mean(cnt)), float(np.std(cnt))])
    f.append(n)
    f.extend(_aggregates(np.strings.str_len(arr)))
    n_none = data.size - data.dropna().size - int((data == "").sum())
    f.extend([int(n_none > 0), n_none / n, n_none, int(n_none == n)])
    return f


def table_profiles(df):
    """{column: profile}, built from df.T exactly as get_individual_features (so numeric upcasts match the release)."""
    if df.shape[1] == 0:
        return {}
    rows = df.T.values.tolist()
    return {c: _column_profile(pd.Series(v).astype(str)) for c, v in zip(df.columns, rows)}


# ------------------------------------------------------------------------------ column-name tokens (tools.py)


class _WordSplitter:
    """wordninja's LanguageModel.split (Zipf word costs, dynamic program, digit / 's re-attachment)."""

    def __init__(self, words):
        words = list(dict.fromkeys(words))
        n = max(len(words), 2)
        self.cost = {w: math.log((i + 1) * math.log(n)) for i, w in enumerate(words)}
        self.maxword = max((len(w) for w in words), default=1)

    def split(self, s):
        out = []
        for part in re.split(r"[^a-zA-Z0-9']+", s):
            out.extend(self._split(part))
        return out

    def _split(self, s):
        cost = [0.0]

        def best(i):
            cands = enumerate(reversed(cost[max(0, i - self.maxword):i]))
            return min((c + self.cost.get(s[i - k - 1:i].lower(), 9e999), k + 1) for k, c in cands)

        for i in range(1, len(s) + 1):
            cost.append(best(i)[0])
        out, i = [], len(s)
        while i > 0:
            _, k = best(i)
            new = True
            if s[i - k:i] != "'" and out:
                if out[-1] == "'s" or (s[i - 1].isdigit() and out[-1][0].isdigit()):
                    out[-1] = s[i - k:i] + out[-1]
                    new = False
            if new:
                out.append(s[i - k:i])
            i -= k
        return list(reversed(out))


def _camel(s):
    return [m.group(0).lower() for m in re.finditer(r".+?(?:(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|$)", s)]


def header_tokens(name, splitter):
    parts = [t for t in re.split(r"_| |/|(\d+)", name) if t]
    flat = list(itertools.chain(*[_camel(t) for t in parts]))
    return list(itertools.chain(*[splitter.split(t) for t in flat]))


@lru_cache(maxsize=4)
def _wordlist_file(path):
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as fh:
        return tuple(w.strip() for w in fh if w.strip())


# ------------------------------------------------------------------------------------------------ word vectors


class _Vectors:
    def __init__(self, words, W, G=None, min_n=0, max_n=0, bucket=0):
        self.index = {w: i for i, w in enumerate(words)}
        self.words, self.W, self.G = words, W, G
        self.min_n, self.max_n, self.bucket = min_n, max_n, bucket
        self._splitter = None

    def vector(self, token):
        """fastText get_word_vector (subword mean for OOV) on the reduced matrices; None for OOV without subwords."""
        i = self.index.get(token)
        if i is not None:
            return self.W[i]
        if self.G is None or self.bucket == 0:
            return None
        from gensim.models.fasttext import ft_ngram_hashes
        h = ft_ngram_hashes(token, self.min_n, self.max_n, self.bucket)
        if len(h) == 0:
            return np.zeros(self.W.shape[1], dtype=np.float32)  # fastText returns the origin
        return self.G[np.asarray(h)].mean(axis=0)

    def splitter(self):
        if self._splitter is None:
            pat = re.compile(r"[a-z0-9']+")
            self._splitter = _WordSplitter(list(itertools.islice((w for w in self.words if pat.fullmatch(w)), 126000)))
        return self._splitter


def _pca_basis(M, dim):
    X = np.asarray(M, dtype=np.float64)
    X = X - X.mean(axis=0)
    C = X.T @ X / max(X.shape[0] - 1, 1)
    vals, vecs = np.linalg.eigh(C)
    return vecs[:, np.argsort(-vals)[:dim]].astype(np.float32)


@lru_cache(maxsize=2)
def load_vectors(path: str, dim: int, pca_rows: int = 100000, limit=None, cache: str = ""):
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(f"omnimatch_release: word-vector file not found: {path}")
    st = p.stat()
    key = f"{p.resolve()}|{st.st_size}|{int(st.st_mtime)}|{dim}|{pca_rows}|{limit}"
    if cache and Path(cache).is_file():
        z = np.load(cache, allow_pickle=False)
        if str(z["key"]) == key:
            words = bytes(z["words"]).decode("utf-8").split("\n")
            G = z["G"] if z["G"].size else None
            mn, mx, bk = (int(x) for x in z["meta"])
            return _Vectors(words, z["W"], G, mn, mx, bk)
    if p.suffix.lower() == ".bin":
        from gensim.models.fasttext import load_facebook_vectors
        kv = load_facebook_vectors(str(p))
        words, base = list(kv.index_to_key), getattr(kv, "vectors_vocab", kv.vectors)
        V = _pca_basis(base[:pca_rows], dim) if dim < kv.vector_size else None
        if dim > kv.vector_size:
            raise ValueError(f"omnimatch_release: dim {dim} exceeds the vectors' dimension {kv.vector_size}")
        proj = (lambda M: np.asarray(M @ V, dtype=np.float32)) if V is not None else (lambda M: np.asarray(M, np.float32))
        W, G = proj(kv.vectors), proj(kv.vectors_ngrams)
        mn, mx, bk = kv.min_n, kv.max_n, kv.bucket
        del kv, base
    else:
        from gensim.models import KeyedVectors
        kv = KeyedVectors.load_word2vec_format(str(p), binary=False, limit=limit)
        if dim > kv.vector_size:
            raise ValueError(f"omnimatch_release: dim {dim} exceeds the vectors' dimension {kv.vector_size}")
        words = list(kv.index_to_key)
        W = kv.vectors @ _pca_basis(kv.vectors[:pca_rows], dim) if dim < kv.vector_size else kv.vectors
        W, G, mn, mx, bk = np.asarray(W, dtype=np.float32), None, 0, 0, 0
        del kv
    if cache:
        tmp = f"{cache}.tmp.npz"
        np.savez(tmp, key=np.array(key), words=np.frombuffer("\n".join(words).encode("utf-8"), dtype=np.uint8), W=W,
                 G=G if G is not None else np.zeros((0, W.shape[1]), np.float32), meta=np.array([mn, mx, bk]))
        os.replace(tmp, cache)
    return _Vectors(words, W, G, mn, mx, bk)


def _vectors_from(options):
    path = options.get("vectors") or os.environ.get("SCHEMATCH_FASTTEXT")
    if not path:
        raise FileNotFoundError("omnimatch_release needs word vectors: set options['vectors'] or env SCHEMATCH_FASTTEXT "
                                "to a fastText .bin (e.g. cc.en.300.bin) or a word2vec .vec file")
    limit = options.get("vectors_limit")
    cache = options.get("vectors_cache") or os.environ.get("SCHEMATCH_FASTTEXT_CACHE") or ""
    return load_vectors(str(path), int(options.get("dim", 50)), int(options.get("pca_rows", 100000)),
                        int(limit) if limit else None, str(cache))


# ------------------------------------------------------------------------------------- pair features (featurizer)

_VTOK = re.compile(r"[^0-9a-zA-Z]")


class _Column:
    def __init__(self, series: pd.Series, header: str, vecs: _Vectors, splitter, js_values: bool):
        vals = [s[:-2] if s.endswith(".0") else s for s in (str(v).lower() for v in series.dropna())]
        self.values = set(vals)
        toks = {v: [t for t in _VTOK.split(v) if t] for v in self.values}
        freq = Counter(t for v in vals for t in toks[v])
        self.infrequent, frequent = set(), set()
        value_tokens = []
        for v in vals:
            tv = toks[v]
            if tv:
                self.infrequent.add(min(tv, key=lambda t: freq[t]))
                frequent.add(max(tv, key=lambda t: freq[t]))
                value_tokens.extend(tv)
        embs = [e for e in (vecs.vector(t) for t in sorted(frequent)) if e is not None]
        self.emb = np.nanmean(np.array(embs, dtype=np.float64), axis=0) if embs else None
        self.dist_tokens = value_tokens if js_values else header_tokens(header.rstrip(), splitter)


def _js_similarity(a, b):
    pa = {t: c / len(a) for t, c in Counter(a).items()}
    pb = {t: c / len(b) for t, c in Counter(b).items()}
    kl_a = sum(f * math.log(f / (0.5 * (f + pb.get(t, 0.0)))) for t, f in pa.items())
    kl_b = sum(f * math.log(f / (0.5 * (f + pa.get(t, 0.0)))) for t, f in pb.items())
    return 1.0 - 0.5 * (kl_a + kl_b)


def pair_features(a: _Column, b: _Column):
    union = a.infrequent | b.infrequent
    jf = len(a.infrequent & b.infrequent) / len(union) if union else 0.0
    if a.emb is None or b.emb is None:
        ve = 0.0
    else:
        with np.errstate(all="ignore"):
            ve = float(np.dot(a.emb, b.emb) / np.linalg.norm(a.emb) / np.linalg.norm(b.emb))
        ve = 0.0 if not math.isfinite(ve) else ve
    vd = _js_similarity(a.dist_tokens, b.dist_tokens)
    inter = len(a.values & b.values)
    un = len(a.values | b.values)
    jac = inter / un if un else 0.0
    c1 = inter / len(b.values) if b.values else 0.0
    c2 = inter / len(a.values) if a.values else 0.0
    return (jf, ve, vd, jac, max(c1, c2))


def topk_edges(pairs, feats, k):
    """create_edge_list_topk: (src, dst, type) lists; pairs are (node_id, node_id), feats the matching 5-tuples."""
    neighbours = {}
    for (u, v), f in zip(pairs, feats):
        neighbours.setdefault(u, []).append((v, f))
        neighbours.setdefault(v, []).append((u, f))
    src, dst, typ = [], [], []
    for node, nb in neighbours.items():
        for i in range(N_FEATURES):
            nb.sort(key=lambda x: x[1][i], reverse=True)
            for j in range(min(k, len(nb))):
                if nb[j][1][i] > -1:
                    src.append(nb[j][0])
                    dst.append(node)
                    typ.append(i)
    return src, dst, typ


# ------------------------------------------------------------------------------------------- model (RGCN, dgl)


def _model(in_feats, out_feats, n_rels):
    import torch

    class RelGraphConv(torch.nn.Module):
        """dgl 0.5.3 RelGraphConv(in, out, num_rels) defaults: basis with num_bases = num_rels, bias, self-loop."""

        def __init__(self, i, o, r):
            super().__init__()
            gain = torch.nn.init.calculate_gain("relu")
            self.weight = torch.nn.Parameter(torch.empty(r, i, o))
            torch.nn.init.xavier_uniform_(self.weight, gain=gain)
            self.h_bias = torch.nn.Parameter(torch.zeros(o))
            self.loop_weight = torch.nn.Parameter(torch.empty(i, o))
            torch.nn.init.xavier_uniform_(self.loop_weight, gain=gain)

        def forward(self, x, graph):
            out = torch.zeros(x.shape[0], self.weight.shape[2], dtype=x.dtype)
            for r, (s, d) in enumerate(graph):
                if s.numel():
                    out = out.index_add(0, d, (x @ self.weight[r])[s])
            return out + self.h_bias + x @ self.loop_weight

    class RelGCN(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.conv1 = RelGraphConv(in_feats, out_feats, n_rels)
            self.conv2 = RelGraphConv(out_feats, out_feats, n_rels)

        def forward(self, x, graph):
            h = torch.relu(self.conv1(x, graph))
            return torch.nn.functional.normalize(self.conv2(h, graph))

    return RelGCN()


def _graph(src, dst, typ):
    import torch
    s, d, t = (torch.tensor(x, dtype=torch.long) for x in (src, dst, typ))
    return [(s[t == r], d[t == r]) for r in range(N_FEATURES)]


def _node_matrix(profiles):
    import torch
    X = torch.tensor(np.asarray(profiles, dtype=np.float64), dtype=torch.float32)
    return torch.nn.functional.normalize(X, 2, 0)


def _triplets(join_pairs, non_join_pairs):
    """_get_negative_neighbors (with the release's reset of the second column's list) + get_train_val_nodes."""
    neg = {}
    for a, b in non_join_pairs:
        neg.setdefault(a, []).append(b)
        neg[b] = [a]
    A, P, N = [], [], []
    for a, b in join_pairs:
        anchor, pos = (a, b) if a in neg else (b, a)
        for n in neg.get(anchor, []):
            A.append(anchor)
            P.append(pos)
            N.append(n)
    cut = int(0.9 * len(A))
    return A[:cut], P[:cut], N[:cut]


# ------------------------------------------------------------------------------------------------------ reading

def _read_tables(ctx, side, max_rows):
    out = []
    for f in sorted((Path(ctx["rt"]) / ctx["ds"] / ctx["sc"] / side).glob("*.csv")):
        with open(f, encoding="utf-8", errors="replace", newline="") as fh:
            header = next(csv.reader(fh), None) or []
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            df = pd.read_csv(f, nrows=max_rows or None, encoding="utf-8", encoding_errors="replace")
        df.columns = [str(c).rstrip() for c in df.columns]
        labels = {f"{f.stem}.{h}": (df.columns[j], h) for j, h in reversed(list(enumerate(header))) if j < df.shape[1]}
        out.append((f"{side}/{f.stem}", df, labels))
    return out


# --------------------------------------------------------------------------------------------------------- main

def omnimatch(ctx: dict, options: dict) -> np.ndarray:
    """|rows| x |cols| similarity 1 - d/2 from the release's rgcn_margin OmniMatch trained on self-generated pairs."""
    options = dict(options or {})
    arm = str(options.get("arm", "full"))
    if arm not in ARMS:
        raise ValueError(f"unknown OmniMatch arm {arm!r}; choose from {ARMS}")
    vecs = _vectors_from(options)
    import torch
    if not ctx["rows"] or not ctx["cols"]:
        return np.zeros((len(ctx["rows"]), len(ctx["cols"])))
    splitter = _WordSplitter(_wordlist_file(str(options["wordlist"]))) if options.get("wordlist") else vecs.splitter()
    js_values = str(options.get("js_tokens", "header")) == "values"
    seed = int(options.get("seed", 20261015))
    k = int(options.get("k", 3))
    R = _Rand(seed)
    max_rows = int(options.get("max_rows", x105.MAXROWS) or 0)
    tables = _read_tables(ctx, "source", max_rows) + _read_tables(ctx, "target", max_rows)

    # 1. self-supervised training pairs, featurised as the release does for its train_tables directory
    gen = generate_pairs([(name, df) for name, df, _ in tables], options, R)
    prof, colobj = [], []  # per training node: profile, _Column
    join_by_pair, nonjoin_by_pair = [], []
    for name, it, t0, t1, common in gen:
        side_ids = []
        for t in (t0, t1):
            profiles = table_profiles(t)
            idx = {}
            for c in t.columns:
                idx[c] = len(prof)
                prof.append(profiles[c])
                colobj.append(_Column(t[c], c, vecs, splitter, js_values))
            side_ids.append(idx)
        i0, i1 = side_ids
        joins = [(i0[c], i1[c]) for c in common if c in i0 and c in i1]
        js = set(joins)
        join_by_pair.append(joins)
        nonjoin_by_pair.append([(a, b) for a in i0.values() for b in i1.values() if (a, b) not in js])
    # the release merges all join pairs first, then all non-join pairs (join_features.update(non_join_features))
    tr_pairs = [p for lst in join_by_pair for p in lst] + [p for lst in nonjoin_by_pair for p in lst]
    tr_feats = [pair_features(colobj[a], colobj[b]) for a, b in tr_pairs]
    src, dst, typ = topk_edges(tr_pairs, tr_feats, k)

    # sampled generated pairs (number_of_sources sources x number_of_datasets datasets) -> triplets
    n_sources = int(options.get("number_of_sources", 20))
    n_datasets = int(options.get("number_of_datasets", 2))
    names = list(dict.fromkeys(g[0] for g in gen))
    chosen = set(R.sample(names, min(n_sources, len(names))))
    use = [gi for gi, g in enumerate(gen) if g[0] in chosen and 2 * g[1] + 1 < n_datasets]
    J = [p for gi in use for p in join_by_pair[gi]]
    NJ = [p for gi in use for p in nonjoin_by_pair[gi]]
    A, P, N = _triplets(J, NJ)

    # 2. test graph over the scenario's source and target columns
    S = {lab: (name, df, c) for name, df, labs in tables if name.startswith("source/") for lab, (c, _) in labs.items()}
    T = {lab: (name, df, c) for name, df, labs in tables if name.startswith("target/") for lab, (c, _) in labs.items()}
    tprof_cache = {}

    def test_node(lab, side):
        hit = side.get(lab)
        if hit is None:
            s = pd.Series([np.nan])
            return _column_profile(s.astype(str)), _Column(s, lab.split(".", 1)[-1], vecs, splitter, js_values)
        name, df, c = hit
        if name not in tprof_cache:
            tprof_cache[name] = table_profiles(df)
        return tprof_cache[name][c], _Column(df[c], c, vecs, splitter, js_values)

    rows, cols_ = list(ctx["rows"]), list(ctx["cols"])
    te = [test_node(l, S) for l in rows] + [test_node(l, T) for l in cols_]
    ns = len(rows)
    te_pairs = [(i, ns + j) for i in range(ns) for j in range(len(cols_))]
    te_feats = [pair_features(te[a][1], te[b][1]) for a, b in te_pairs]
    te_edges = topk_edges(te_pairs, te_feats, k)
    te_graph = _graph(*te_edges)

    if arm == "random_forest":
        return _random_forest(tr_pairs, tr_feats, join_by_pair, te_feats, ns, len(cols_), seed)

    # 3. train (full batch, triplet margin loss) and embed the test graph inductively; deterministic kernels because the
    # CPU backward of repeated-index gathers accumulates in a thread-dependent order otherwise
    was_det = torch.are_deterministic_algorithms_enabled()
    torch.use_deterministic_algorithms(True)
    try:
        if arm == "full":
            Z = _train_and_embed(torch, seed, options, prof, (src, dst, typ), (A, P, N), [t[0] for t in te], te_graph)
        else:  # no edges in the training and the test graph: only the self-loop weights act
            Z, Ztr = _train_and_embed(torch, seed, options, prof, ([], [], []), (A, P, N), [t[0] for t in te],
                                      _graph([], [], []), with_train=True)
    finally:
        torch.use_deterministic_algorithms(was_det)
    D = torch.cdist(Z[:ns].double(), Z[ns:].double()).numpy()
    out = np.clip(np.nan_to_num(1.0 - D / 2.0, nan=0.0), 0.0, 1.0)
    if arm == "one_step_edges":
        gamma = _edge_gamma(torch, Ztr, (A, P, N), _edge_counts(tr_pairs, tr_feats, k))
        c_te = _edge_counts(te_pairs, te_feats, k)
        C = np.array([[c_te.get(frozenset((i, ns + j)), 0) for j in range(len(cols_))] for i in range(ns)], dtype=float)
        out = out + gamma * C / N_FEATURES
    return out


# Ablation arms (information-matched controls for the graph propagation):
#   full            the release's model (above)
#   no_edges        same features and training, but no edges in the training or test graph (self-loops only)
#   one_step_edges  no_edges + gamma * (number of the five similarities under which s and t are among each other's top-k)
#                   / 5, without propagation; gamma from EDGE_GAMMAS, chosen on the generated training triplets only
#                   (share of triplets ranked correctly, first best wins)
#   random_forest   the release's rf_predictor baseline: a random forest (sklearn, random_state = seed) on the five pair
#                   similarities of the generated pairs, join = positive; its join probability is the score
ARMS = ("full", "no_edges", "one_step_edges", "random_forest")
EDGE_GAMMAS = (0.0, 0.05, 0.1, 0.2, 0.5, 1.0)


def _edge_counts(pairs, feats, k) -> dict:
    """For each unordered node pair, under how many of the five similarities one is among the other's top-k."""
    src, dst, typ = topk_edges(pairs, feats, k)
    rels = {}
    for s, d, t in zip(src, dst, typ):
        rels.setdefault(frozenset((s, d)), set()).add(t)
    return {p: len(r) for p, r in rels.items()}


def _edge_gamma(torch, Ztr, triplets, counts) -> float:
    A, P, N = triplets
    if not A or Ztr is None:
        return 0.0

    def score(u, v):
        return 1.0 - float(torch.dist(Ztr[u].double(), Ztr[v].double())) / 2.0
    best, best_g = -1.0, 0.0
    for g in EDGE_GAMMAS:
        ok = sum((score(a, p) + g * counts.get(frozenset((a, p)), 0) / N_FEATURES)
                 > (score(a, n) + g * counts.get(frozenset((a, n)), 0) / N_FEATURES) for a, p, n in zip(A, P, N))
        if ok / len(A) > best + 1e-12:
            best, best_g = ok / len(A), g
    return best_g


def _random_forest(tr_pairs, tr_feats, join_by_pair, te_feats, ns, nt, seed) -> np.ndarray:
    from sklearn.ensemble import RandomForestClassifier
    joins = {p for lst in join_by_pair for p in lst}
    y = np.array([p in joins for p in tr_pairs], dtype=int)
    if y.size == 0 or y.min() == y.max():
        return np.zeros((ns, nt))
    clf = RandomForestClassifier(random_state=seed).fit(np.array(tr_feats), y)
    return clf.predict_proba(np.array(te_feats))[:, 1].reshape(ns, nt)


def _train_and_embed(torch, seed, options, prof, edges, triplets, test_prof, te_graph, with_train=False):
    src, dst, typ = edges
    A, P, N = triplets
    with torch.random.fork_rng():
        torch.manual_seed(seed)
        X = _node_matrix(prof) if prof else None
        G = _graph(src, dst, typ)
        net = _model(len(test_prof[0]), int(options.get("dimension", 256)), N_FEATURES)
        opt = torch.optim.Adam(net.parameters(), lr=float(options.get("lr", 0.001)), weight_decay=0)
        loss_fn = torch.nn.TripletMarginLoss(margin=float(options.get("margin", 0.5)), p=float(options.get("norm", 2)))
        if A and X is not None:
            a, p, n = (torch.tensor(x, dtype=torch.long) for x in (A, P, N))
            for _ in range(int(options.get("epochs", 30))):
                opt.zero_grad()
                h = net(X, G)
                loss = loss_fn(h[a], h[p], h[n])
                loss.backward()
                opt.step()
        with torch.no_grad():
            Z = net(_node_matrix(test_prof), te_graph)
            if with_train:
                return Z, (net(X, G) if X is not None else None)
            return Z
