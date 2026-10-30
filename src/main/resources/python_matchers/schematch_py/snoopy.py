"""Snoopy (Guo et al., "Effective and Efficient Semantic Join Discovery via Proxy Columns", 2025) as a column-pair matcher.

Re-implements the inference path of ZJU-DAILY/Snoopy (commit 0737f3a, read 2026-10-15; the released search code does not run
as published) around its released proxy columns: each cell is a fastText cc.en.300 sentence vector (fastText's
get_sentence_vector for unsupervised models: the mean of the L2-normalised word vectors of the whitespace tokens), a column is
the L2-normalised sum over the 50 proxy elements of the max similarity to its cells, for each of the 90 proxy columns
(proxy_sets: 90 x 50 x 300), and a pair scores the cosine of the two column embeddings, mapped to [0, 1].

Snoopy is a semantic *join* signal (cell-level value correspondence), not a schema matcher; its checkpoints were trained on
WikiTable, DeepJoin's OpenData and WDC. The default is the WikiTable checkpoint; "opendata" may overlap open-government
benchmarks. Deviations: cells are the column's distinct non-null values (at most options["max_cells"], default 1000, a seeded
sample beyond that); the released repository has no licence file, so this is for internal research comparison only.

Resources: options["checkpoint_dir"] (or SCHEMATCH_SNOOPY) -> the repository's check/ directory, options["variant"] in
{"WikiTable", "WDC", "opendata"}; options["vectors"] (or SCHEMATCH_FASTTEXT) -> cc.en.300.bin (fastText binary, may be .gz).
"""
from __future__ import annotations

import os
from functools import lru_cache

import numpy as np

NULLS = {"", "null", "none", "nan", "na", "n/a", "-", "\\n"}


@lru_cache(maxsize=1)
def fasttext_vectors(path: str):
    from gensim.models.fasttext import load_facebook_vectors
    return load_facebook_vectors(path)


@lru_cache(maxsize=3)
def proxy_sets(checkpoint: str) -> np.ndarray:
    import torch
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    return state["proxy_sets"].numpy().astype(np.float32)


def sentence_vector(text: str, wv) -> np.ndarray:
    out, n = np.zeros(wv.vector_size, dtype=np.float32), 0
    for word in text.replace("\n", " ").split():
        v = wv[word]
        norm = float(np.linalg.norm(v))
        if norm > 0:
            out += v / norm
            n += 1
    return out / n if n else out


def column_embedding(values, wv, P, max_cells, rng) -> np.ndarray:
    cells = sorted({v.strip() for v in values if v is not None and v.strip().lower() not in NULLS})
    if len(cells) > max_cells:
        cells = [cells[i] for i in sorted(rng.choice(len(cells), max_cells, replace=False))]
    if not cells:
        return np.zeros(P.shape[0], dtype=np.float32)
    X = np.stack([sentence_vector(c, wv) for c in cells])  # M x d
    t = np.einsum("hmd,nd->hmn", P, X).max(axis=2).sum(axis=1)  # max over cells, sum over elements: h
    return t / (np.linalg.norm(t) + 1e-12)


def scores(s_cols, t_cols, options) -> np.ndarray:
    root = options.get("checkpoint_dir") or os.environ.get("SCHEMATCH_SNOOPY")
    vectors = options.get("vectors") or os.environ.get("SCHEMATCH_FASTTEXT")
    if not root or not vectors:
        raise FileNotFoundError("snoopy needs options.checkpoint_dir / SCHEMATCH_SNOOPY (the repository's check/ directory) and "
                                "options.vectors / SCHEMATCH_FASTTEXT (fastText cc.en.300.bin)")
    variant = options.get("variant", "WikiTable")
    P = proxy_sets(os.path.join(str(root), variant, "t=0.2_gendata_mat_leveltrained.pth"))
    wv = fasttext_vectors(str(vectors))
    rng = np.random.default_rng(int(options.get("seed", 20261015)))
    k = int(options.get("max_cells", 1000))
    A = np.stack([column_embedding(c, wv, P, k, rng) for c in s_cols])
    B = np.stack([column_embedding(c, wv, P, k, rng) for c in t_cols])
    return np.clip((A @ B.T + 1.0) / 2.0, 0.0, 1.0)
