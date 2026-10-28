"""Scenario-level learned / holistic matchers, added 2026-10-15. They read the scenario's source and target tables
(multitable.read_all, labels "table.column"), need no initial matcher (ctx["base"] is ignored) and return a |S| x |T| matrix.

ALITE (Khatiwada et al., VLDB 2023) and RACT (Traeger et al.) follow the published code (northeastern-datalab/alite 18f2123,
leotraeg/RACT 19a2f1b, read 2026-10-15; no code copied), with the deviations noted in each function: ALITE's output is
clusters only, so the score composition is ours; RACT reads declared foreign keys, which the scenarios do not have, so
references come from INDs. OmniMatch (Koutras et al., 2024) is a reimplementation from the paper with mpnet value embeddings
instead of fastText and a plain-torch RGCN; see omnimatch().
"""
from __future__ import annotations

import math
import re
import warnings
from collections import Counter, defaultdict, deque
from functools import lru_cache

import numpy as np

from . import multitable as x105

NULLS = {"", "null", "none", "nan", "na", "n/a", "-", "\\n"}


@lru_cache(maxsize=2)
def encoder(name: str):
    from sentence_transformers import SentenceTransformer
    return SentenceTransformer(name)


def column_values(ctx):
    """{label: non-null values} for the scenario's source and target columns (missing labels get no values)."""
    vs = x105.read_all(ctx["rt"], ctx["ds"], ctx["sc"], "source")
    vt = x105.read_all(ctx["rt"], ctx["ds"], ctx["sc"], "target")
    clean = lambda col: [v for v in col if v.strip().lower() not in NULLS]  # noqa: E731
    return [clean(vs.get(l, [])) for l in ctx["rows"]], [clean(vt.get(l, [])) for l in ctx["cols"]]


def serialise(label, values, k, rng=None, header=True):
    distinct = list(dict.fromkeys(values))
    if rng is not None and len(distinct) > k:
        distinct = [distinct[i] for i in sorted(rng.choice(len(distinct), k, replace=False))]
    body = ", ".join(distinct[:k])
    return f"{label.split('.', 1)[-1]}: {body}" if header else body


def embed(texts, options):
    model = encoder(options.get("model", "all-mpnet-base-v2"))
    return np.asarray(model.encode(list(texts), normalize_embeddings=True, batch_size=64), dtype=np.float32)


def cos01(A, B):
    return ((A @ B.T) + 1.0) / 2.0


# ------------------------------------------------------------------------------------------------------------------ ALITE

@lru_cache(maxsize=2)
def roberta(path: str, seed: int):
    import torch
    from transformers import AutoModel, AutoTokenizer
    # roberta-base ships no pooler weights: from_pretrained initialises the pooler randomly (as in ALITE's own runs). Seeding
    # makes that initialisation, and so the "pooler" embedding, reproducible.
    torch.manual_seed(seed)
    model = AutoModel.from_pretrained(path, local_files_only=True)
    model.eval()
    return model, AutoTokenizer.from_pretrained(path, local_files_only=True)


def roberta_embeddings(texts, options):
    """ALITE's `roberta_serialized` embedding (512 tokens). pooling "pooler" (default, as ALITE: pooler_output, whose dense
    layer is untrained in roberta-base), "cls" (last hidden state at <s>) or "mean" (attention-masked mean)."""
    import os
    import torch
    path = options.get("roberta") or os.environ.get("SCHEMATCH_ROBERTA") or "FacebookAI/roberta-base"
    model, tok = roberta(str(path), int(options.get("seed", 20261015)))
    pooling = options.get("pooling", "pooler")
    out = []
    for start in range(0, len(texts), 16):
        enc = tok(list(texts[start:start + 16]), padding=True, truncation=True, max_length=512, return_tensors="pt")
        with torch.no_grad():
            res = model(**enc)
        if pooling == "pooler":
            out.append(res.pooler_output.numpy())
        elif pooling == "cls":
            out.append(res.last_hidden_state[:, 0].numpy())
        else:
            m = enc["attention_mask"].unsqueeze(-1).float()
            out.append(((res.last_hidden_state * m).sum(1) / m.sum(1)).numpy())
    return np.concatenate(out) if out else np.zeros((0, 768), dtype=np.float32)


def alite_documents(columns, max_tokens=512):
    """ALITE's value serialisation: distinct values lower-cased, parentheses removed, split into unique alphanumeric tokens;
    the max_tokens tokens with the highest IDF over all columns (TF is 1 for unique tokens), joined by spaces. No header."""
    toks = []
    for vals in columns:
        s = set()
        for v in set(vals):
            s.update(re.findall(r"[a-z0-9]+", re.sub(r"[()]", "", v.lower())))
        toks.append(s)
    df, n = Counter(t for s in toks for t in s), max(len(toks), 1)
    return [" ".join(sorted(s, key=lambda t: (-math.log(n / df[t]), t))[:max_tokens]) for s in toks]


def is_numeric(values, share=0.9):
    nums = sum(_number(v) is not None for v in values)
    return bool(values) and nums >= share * len(values)


def alite(ctx, options):
    """ALITE (align_integration_ids.py / align_phase.py): sklearn agglomerative clustering of the string columns of all tables
    with >= 3 rows, Euclidean metric, complete linkage, connectivity 0 between columns of one table (the original's must-link
    restriction, looser than a cannot-link); k from the widest table to n - 1 by Euclidean silhouette. ALITE outputs clusters
    only; the score here is (cos01 + bonus * same_cluster) / (1 + bonus), cosine over the same embeddings for every column."""
    from sklearn.cluster import AgglomerativeClustering
    from sklearn.metrics import silhouette_score
    S_vals, T_vals = column_values(ctx)
    rs = x105.read_all(ctx["rt"], ctx["ds"], ctx["sc"], "source")
    rt = x105.read_all(ctx["rt"], ctx["ds"], ctx["sc"], "target")
    labels = ctx["rows"] + ctx["cols"]
    nrows = [len(rs.get(l, [])) for l in ctx["rows"]] + [len(rt.get(l, [])) for l in ctx["cols"]]
    values = S_vals + T_vals
    docs = [d or "empty" for d in alite_documents(values, int(options.get("max_tokens", 512)))]
    E = roberta_embeddings(docs, options) if options.get("encoder", "roberta") == "roberta" else embed(docs, options)
    E = np.asarray(E, dtype=np.float64)
    ns = len(ctx["rows"])
    En = E / (np.linalg.norm(E, axis=1, keepdims=True) + 1e-12)
    cos = cos01(En[:ns], En[ns:])
    groups = np.array([f"s|{x105.table_of(l)}" for l in ctx["rows"]] + [f"t|{x105.table_of(l)}" for l in ctx["cols"]])
    use = np.array([n >= 3 and (not options.get("strings_only", True) or not is_numeric(v)) for n, v in zip(nrows, values)])
    idx = np.flatnonzero(use)
    cluster = np.full(len(labels), -1)
    if len(idx) >= 3:
        X, g = E[idx], groups[idx]
        conn = (g[:, None] != g[None, :]).astype(int)
        lo = max(Counter(g).values())
        best, best_s = None, -np.inf
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            for k in range(max(lo, 2), len(idx)):
                lab = AgglomerativeClustering(n_clusters=k, metric="euclidean", linkage=options.get("linkage", "complete"),
                                              connectivity=conn).fit_predict(X)
                s = silhouette_score(X, lab) if len(set(lab)) > 1 else -np.inf
                if s > best_s:
                    best, best_s = lab, s
        if best is not None:
            cluster[idx] = best
    cs, ct = cluster[:ns], cluster[ns:]
    same = ((cs[:, None] == ct[None, :]) & (cs[:, None] >= 0)).astype(float)
    bonus = float(options.get("bonus", 0.5))
    return (cos + bonus * same) / (1.0 + bonus)


# ------------------------------------------------------------------------------------------------------------------- RACT

def side_structure(ctx, side, labels, values, near_key=0.95, containment=0.9):
    """Keys, foreign keys and the directed table graph (child -> parent) of one side. RACT reads declared FKs; the scenarios
    have none, so a reference is an IND from the profiles or a partial IND (>= containment of the distinct values) into a key
    of another table; a key is a profiled single-column UCC or a column with >= near_key distinct values and >= 2 rows."""
    try:
        _, uccs, inds = x105.profiles(ctx["rt"], ctx["ds"], ctx["sc"], side)
    except OSError:
        uccs, inds = set(), []
    sets = {l: set(v) for l, v in zip(labels, values)}
    keys = {l for l, v in zip(labels, values) if l in uccs or (len(v) >= 2 and len(sets[l]) / len(v) >= near_key)}
    refs = {(a, b) for a, b in inds if a in sets and b in keys and x105.table_of(a) != x105.table_of(b)}
    for a in labels:
        if not sets[a]:
            continue
        for b in keys:
            if x105.table_of(a) != x105.table_of(b) and len(sets[a] & sets[b]) / len(sets[a]) >= containment:
                refs.add((a, b))
    fks = {a for a, _ in refs if a not in keys}
    parents, children = defaultdict(set), defaultdict(set)
    for a, b in refs:
        parents[x105.table_of(a)].add(x105.table_of(b))
        children[x105.table_of(b)].add(x105.table_of(a))
    return keys, fks, parents, children


def ract_type(values):
    if values and all(re.fullmatch(r"[-+]?\d+", v) for v in values[:2000]):
        return "NUMBER"
    if is_numeric(values, 1.0):
        return "FLOAT"
    if values and all(re.fullmatch(r"\d{4}-\d{2}-\d{2}([ T].*)?", v) for v in values[:2000]):
        return "DATE"
    return "VARCHAR2"


def ract_texts(labels, values, structure):
    """RACT's SerReference serialisation: "[CLS] Column: NAME [SEP] Table: T [SEP] Type: .. [SEP] Constraint: .. [SEP]
    Values: {5 distinct values, <= 300 chars} [SEP] Weak Tables: children [SEP] Strong Tables: parents" (names upper-cased)."""
    keys, fks, parents, children = structure
    out = []
    for l, v in zip(labels, values):
        t, c = x105.table_of(l), l.split(".", 1)[-1]
        constraint = "PRIMARY KEY" if l in keys else "FOREIGN KEY" if l in fks else "None"
        weak = " ".join(sorted(children[t])).upper() or "None"
        strong = " ".join(sorted(parents[t])).upper() or "None"
        inst = ", ".join(list(dict.fromkeys(v))[:5])[:300] or "None"
        parts = [f"Column: {c.upper()}", f"Table: {t.upper()}", f"Type: {ract_type(v)}", f"Constraint: {constraint}",
                 "Values: {" + inst + "}", f"Weak Tables: {weak}", f"Strong Tables: {strong}"]
        out.append("[CLS] " + " [SEP] ".join(parts))
    return out


def focal_loss(p, y, alpha, gamma):
    """RACT's multi-label focal loss: alpha * (1 - pt)^gamma * BCE with pt = exp(-BCE), alpha on both terms."""
    import torch
    p = p.clamp(1e-7, 1 - 1e-7)
    ll = y * torch.log(p) + (1 - y) * torch.log(1 - p)
    return (-alpha * (1 - torch.exp(ll)) ** gamma * ll).mean()


def ract_training_set(cols, E, tables, children, decay):
    """Original columns with one-hot labels, plus view copies along every 1-hop join path (parent with each child): each
    column of the view keeps 1 for its own table and gets exp(-decay) for the other table of the path."""
    tix = {t: n for n, t in enumerate(tables)}
    own = np.array([tix[x105.table_of(l)] for l in cols])
    X, Y, strat = [E], [np.eye(len(tables), dtype=np.float32)[own]], [own]
    w = math.exp(-decay)
    for parent in tables:
        for child in sorted(children[parent]):
            if child not in tix:
                continue
            for a, b in ((parent, child), (child, parent)):
                m = own == tix[a]
                if m.any():
                    y = np.eye(len(tables), dtype=np.float32)[own[m]]
                    y[:, tix[b]] = w
                    X.append(E[m]); Y.append(y); strat.append(own[m])
    return np.concatenate(X), np.concatenate(Y), np.concatenate(strat)


def ract(ctx, options):
    """RACT b.I: a column-to-table classifier trained on the target schema, then cosine within each source column's top-t
    target tables (0.01 x cosine outside, so the ranking stays complete; RACT drops those columns)."""
    import torch
    S_vals, T_vals = column_values(ctx)
    seed = int(options.get("seed", 42))
    s_struct = side_structure(ctx, "source", ctx["rows"], S_vals)
    t_struct = side_structure(ctx, "target", ctx["cols"], T_vals)
    Es = embed(ract_texts(ctx["rows"], S_vals, s_struct), options)
    Et = embed(ract_texts(ctx["cols"], T_vals, t_struct), options)
    C = cos01(Es, Et)
    tables = sorted({x105.table_of(l) for l in ctx["cols"]})
    if len(tables) < 2:
        return C
    X, Y, strat = ract_training_set(ctx["cols"], Et, tables, t_struct[3], float(options.get("decay", 0.5)))
    folds = int(options.get("folds", 5))
    smallest = int(np.bincount(strat).min())
    if min(folds, smallest) >= 2:
        from sklearn.model_selection import StratifiedKFold
        splits = [tr for tr, _ in StratifiedKFold(n_splits=min(folds, smallest), shuffle=True, random_state=seed).split(X, strat)]
    else:
        splits = [np.arange(len(X))]
    alpha, gamma = float(options.get("alpha", 0.9)), float(options.get("gamma", 2.0))
    l2, drop = float(options.get("l2", 0.001)), float(options.get("dropout", 0.2))
    epochs, batch = int(options.get("epochs", 100)), int(options.get("batch_size", 16))
    torch.manual_seed(seed)
    g = torch.Generator().manual_seed(seed)
    P = np.zeros((len(ctx["rows"]), len(tables)))
    for tr in splits:
        h1, h2 = torch.nn.Linear(X.shape[1], 512), torch.nn.Linear(512, 256)
        net = torch.nn.Sequential(h1, torch.nn.ReLU(), torch.nn.Dropout(drop), h2, torch.nn.ReLU(), torch.nn.Dropout(drop),
                                  torch.nn.Linear(256, len(tables)), torch.nn.Sigmoid())
        opt = torch.optim.Adam(net.parameters(), lr=float(options.get("lr", 1e-3)))
        Xt, Yt = torch.tensor(X[tr], dtype=torch.float32), torch.tensor(Y[tr])
        net.train()
        for _ in range(epochs):
            for b in torch.randperm(len(Xt), generator=g).split(batch):
                opt.zero_grad()
                loss = focal_loss(net(Xt[b]), Yt[b], alpha, gamma) + l2 * (h1.weight.pow(2).sum() + h2.weight.pow(2).sum())
                loss.backward()
                opt.step()
        net.eval()
        with torch.no_grad():
            P += net(torch.tensor(Es, dtype=torch.float32)).numpy() / len(splits)
    t = min(int(options.get("top_tables", 2)), len(tables))
    top = np.argsort(-P, axis=1, kind="stable")[:, :t]
    tix = np.array([tables.index(x105.table_of(l)) for l in ctx["cols"]])
    inside = np.array([np.isin(tix, top[i]) for i in range(len(ctx["rows"]))], dtype=float)
    return C * (inside + 0.01 * (1 - inside))


# -------------------------------------------------------------------------------------------------------------- OmniMatch

def _number(v):
    try:
        return float(v.replace(",", ""))
    except ValueError:
        return None


def sherlock_features(values):
    """A compact Sherlock-style character / statistic profile (16 numbers)."""
    if not values:
        return np.zeros(16)
    lens = np.array([len(v) for v in values], dtype=float)
    chars = "".join(values[:2000])
    n = max(len(chars), 1)
    nums = [x for x in (_number(v) for v in values[:2000]) if x is not None and math.isfinite(x)]
    frac_num = len(nums) / min(len(values), 2000)
    lg = np.sign(nums) * np.log1p(np.abs(nums)) if nums else np.zeros(1)
    cnt = Counter(chars)
    ent = -sum(c / n * math.log2(c / n) for c in cnt.values()) if chars else 0.0
    return np.array([
        np.log1p(lens.mean()), np.log1p(lens.std()), sum(ch.isdigit() for ch in chars) / n, sum(ch.isalpha() for ch in chars) / n,
        sum(ch.isupper() for ch in chars) / n, sum(ch.isspace() for ch in chars) / n, sum(not ch.isalnum() and not ch.isspace() for ch in chars) / n,
        len(set(values)) / len(values), frac_num, float(np.mean(lg)), float(np.std(lg)), ent / 8.0,
        float(np.mean([len(v.split()) for v in values[:2000]])), float(np.mean([bool(re.search(r"\d{4}-\d{2}-\d{2}", v)) for v in values[:500]])),
        float(np.mean([("@" in v) for v in values[:500]])), float(np.mean([v[:1].isupper() for v in values[:500] if v])) if values else 0.0,
    ])


def perturb(values, rng, rate=0.1):
    out = []
    for v in values:
        if v and rng.random() < rate:
            p = int(rng.integers(len(v)))
            v = v[:p] + v[p + 1:] if rng.random() < 0.5 else v.swapcase()
        out.append(v)
    return out


def views(values, rng):
    """Two disjoint row halves of the column, each lightly perturbed (character drop / case swap on 10% of values)."""
    idx = rng.permutation(len(values))
    h = len(values) // 2
    a, b = [values[i] for i in sorted(idx[:h])], [values[i] for i in sorted(idx[h:])]
    return perturb(a or values, rng), perturb(b or values, rng)


def _distribution(a, b):
    from scipy.stats import ks_2samp
    na = [x for x in (_number(v) for v in a[:2000]) if x is not None]
    nb = [x for x in (_number(v) for v in b[:2000]) if x is not None]
    if len(na) >= 0.9 * max(len(a[:2000]), 1) and len(nb) >= 0.9 * max(len(b[:2000]), 1) and na and nb:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            return 1.0 - ks_2samp(na, nb).statistic
    ha = np.bincount(np.minimum([len(v) for v in a], 50), minlength=51) / max(len(a), 1)
    hb = np.bincount(np.minimum([len(v) for v in b], 50), minlength=51) / max(len(b), 1)
    return 1.0 - 0.5 * float(np.abs(ha - hb).sum())


def relations(cols, E, F, top_k):
    """Five weighted relations as dense row-normalised adjacency matrices (top-k neighbours per node, similarity > 0)."""
    n = len(cols)
    sets = [set(c[:5000]) for c in cols]
    J, Ct, Dd = np.zeros((n, n)), np.zeros((n, n)), np.zeros((n, n))
    for i in range(n):
        for j in range(i + 1, n):
            inter = len(sets[i] & sets[j])
            if inter:
                J[i, j] = J[j, i] = inter / len(sets[i] | sets[j])
                Ct[i, j] = Ct[j, i] = inter / max(min(len(sets[i]), len(sets[j])), 1)
            Dd[i, j] = Dd[j, i] = _distribution(cols[i], cols[j]) if cols[i] and cols[j] else 0.0
    Ee = np.clip(E @ E.T, 0, 1)
    Fn = F / (np.linalg.norm(F, axis=1, keepdims=True) + 1e-9)
    Ff = np.clip(Fn @ Fn.T, 0, 1)
    out = []
    for W in (J, Ct, Ee, Dd, Ff):
        W = W.copy()
        np.fill_diagonal(W, 0)
        keep = np.zeros_like(W, dtype=bool)
        nb = np.argsort(-W, axis=1, kind="stable")[:, :top_k]
        keep[np.arange(n)[:, None], nb] = True
        W = np.where(keep | keep.T, W, 0.0)
        out.append(W / (W.sum(axis=1, keepdims=True) + 1e-9))
    return out


def omnimatch(ctx, options):
    import torch
    S_vals, T_vals = column_values(ctx)
    seed = int(options.get("seed", 20261015))
    rng = np.random.default_rng(seed)
    base = S_vals + T_vals
    n0 = len(base)
    v1, v2 = zip(*[views(c, rng) for c in base]) if base else ((), ())
    cols = list(base) + list(v1) + list(v2)
    k = int(options.get("values", 20))
    E = embed([serialise("", c, k, header=False) or "empty" for c in cols], options)
    F = np.array([sherlock_features(c) for c in cols])
    F = (F - F.mean(0)) / (F.std(0) + 1e-9)
    A = [torch.tensor(W, dtype=torch.float32) for W in relations(cols, E, F, int(options.get("top_k", 5)))]
    X = torch.tensor(np.concatenate([E, F], axis=1), dtype=torch.float32)
    torch.manual_seed(seed)

    class RGCN(torch.nn.Module):
        def __init__(self, d_in, d_h, d_out, R):
            super().__init__()
            self.self1, self.self2 = torch.nn.Linear(d_in, d_h), torch.nn.Linear(d_h, d_out)
            self.rel1 = torch.nn.ModuleList(torch.nn.Linear(d_in, d_h, bias=False) for _ in range(R))
            self.rel2 = torch.nn.ModuleList(torch.nn.Linear(d_h, d_out, bias=False) for _ in range(R))

        def forward(self, x):
            h = torch.relu(self.self1(x) + sum(a @ w(x) for a, w in zip(A, self.rel1)))
            return self.self2(h) + sum(a @ w(h) for a, w in zip(A, self.rel2))

    net = RGCN(X.shape[1], int(options.get("hidden", 128)), int(options.get("dim", 64)), len(A))
    opt = torch.optim.Adam(net.parameters(), lr=float(options.get("lr", 1e-3)))
    margin = float(options.get("margin", 0.5))
    anchors = torch.arange(n0) + n0
    positives = torch.arange(n0) + 2 * n0
    g = torch.Generator().manual_seed(seed)
    for _ in range(int(options.get("epochs", 100))):
        Z = torch.nn.functional.normalize(net(X), dim=1)
        shift = torch.randint(1, max(n0, 2), (n0,), generator=g)
        negatives = (torch.arange(n0) + shift) % n0 + 2 * n0
        d_pos = 1 - (Z[anchors] * Z[positives]).sum(1)
        d_neg = 1 - (Z[anchors] * Z[negatives]).sum(1)
        loss = torch.relu(d_pos - d_neg + margin).mean()
        opt.zero_grad()
        loss.backward()
        opt.step()
    with torch.no_grad():
        Z = torch.nn.functional.normalize(net(X), dim=1).numpy()
    ns = len(ctx["rows"])
    return cos01(Z[:ns], Z[ns:n0])
