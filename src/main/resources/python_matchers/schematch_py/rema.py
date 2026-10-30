"""Bundled matcher implementation; see SOURCES.json for provenance.

REMA-ARC: a bounded reconstruction of Koutras et al., "REMA: Graph Embeddings-based Relational Schema Matching",
SEAData 2020. There is no released implementation; this follows the four-page text, and the declared defaults below are
not in the paper. Pipeline:

  1. one undirected Attribute-Row-Cell graph over both relations; a stripped non-null cell value is ONE node shared across
     tuples, columns and the two relations (the bridges between them);
  2. simple uniform random walks (REMA-ARC, not the type-biased REMA-JUST), 10 per node of length 100;
  3. skip-gram Word2Vec (gensim), 128 dimensions, window 10;
  4. column vector = unweighted mean of its distinct cell-value embeddings; score = raw cosine in [-1, 1].

Reads no attribute labels: attribute nodes are (side, position). It is carried entirely by cross-table value identity, so
under per-side salted value recoding the two relations share no node and the cosine is uninformative (expected).

Declared defaults (not in the paper): 1 epoch, negative 5, min_count 1, seed 20260908, walks generated in chunks of 20,000
from default_rng([seed, chunk index]), workers 1 (multi-worker gensim training is not deterministic), SHA-256 word hashing
instead of Python's salted hash(), and the first 1,000 rows of each table (row_cap; 0 = all). Needs gensim.
"""
from __future__ import annotations

import csv
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Sequence

import numpy as np

WALKS_PER_NODE = 10
WALK_LENGTH = 100
DIMENSIONS = 128
WINDOW = 10
SKIP_GRAM = 1
EPOCHS = 1
NEGATIVE = 5
MIN_COUNT = 1
SEED = 20260908
WALK_CHUNK = 20000
ROW_CAP = 1000

ROW, VALUE, ATTRIBUTE = 0, 1, 2
NULLS = {"", "null", "none", "nan", "na", "n/a", "-"}


def is_null(value: str) -> bool:
    return value.strip().lower() in NULLS


@dataclass(frozen=True)
class ArcGraph:
    """CSR graph; `value_nodes[side][column]` lists the node ids of that column's distinct non-null values."""
    indptr: np.ndarray
    indices: np.ndarray
    kind: np.ndarray
    value_nodes: tuple[list[list[int]], list[list[int]]]
    n_rows_used: tuple[int, int]

    @property
    def n_nodes(self) -> int:
        return int(self.indptr.size - 1)

    @property
    def degree(self) -> np.ndarray:
        return np.diff(self.indptr)


def build_arc_graph(source: Sequence[Sequence[str]], target: Sequence[Sequence[str]], row_cap: int = 0) -> ArcGraph:
    """Column-major tables (`side[j][i]` is row i of column j) -> one graph. Row--value and attribute--value edges, both
    directions stored; multi-edges collapsed."""
    sides = (source, target)
    for side in sides:
        if side and len({len(c) for c in side}) != 1:
            raise ValueError("ragged table: columns differ in length")
    rows_used = tuple(min(len(s[0]), row_cap) if s and row_cap > 0 else (len(s[0]) if s else 0) for s in sides)
    node_kind: list[int] = []
    value_id: dict[str, int] = {}

    def new_node(k: int) -> int:
        node_kind.append(k)
        return len(node_kind) - 1

    row_id = [[new_node(ROW) for _ in range(rows_used[s])] for s in (0, 1)]
    attr_id = [[new_node(ATTRIBUTE) for _ in side] for side in sides]
    edges: set[tuple[int, int]] = set()
    value_nodes: tuple[list[list[int]], list[list[int]]] = ([], [])
    for s, side in enumerate(sides):
        for j, column in enumerate(side):
            seen: dict[str, None] = {}
            for i in range(rows_used[s]):
                raw = column[i]
                if is_null(raw):
                    continue
                value = raw.strip()
                node = value_id.get(value)
                if node is None:
                    node = value_id[value] = new_node(VALUE)
                edges.add((row_id[s][i], node))
                seen[value] = None
            for value in seen:
                edges.add((attr_id[s][j], value_id[value]))
            value_nodes[s].append([value_id[v] for v in seen])
    n = len(node_kind)
    if edges:
        left = np.fromiter((e[0] for e in edges), dtype=np.int64, count=len(edges))
        right = np.fromiter((e[1] for e in edges), dtype=np.int64, count=len(edges))
        rows = np.concatenate([left, right])
        cols = np.concatenate([right, left])
        order = np.argsort(rows, kind="stable")
        rows, cols = rows[order], cols[order]
        indptr = np.zeros(n + 1, dtype=np.int64)
        np.add.at(indptr, rows + 1, 1)
        np.cumsum(indptr, out=indptr)
    else:
        cols = np.empty(0, dtype=np.int64)
        indptr = np.zeros(n + 1, dtype=np.int64)
    return ArcGraph(indptr=indptr, indices=np.ascontiguousarray(cols), kind=np.asarray(node_kind, dtype=np.int8),
                    value_nodes=value_nodes, n_rows_used=rows_used)


def walk_starts(graph: ArcGraph, walks_per_node: int = WALKS_PER_NODE, high_degree: bool = False) -> np.ndarray:
    """`walks_per_node` starts per node; high_degree keeps only nodes above the mean degree (the paper's HighDegree)."""
    if high_degree:
        degree = graph.degree
        starts = np.flatnonzero(degree > degree.mean()).astype(np.int64) if degree.size else np.empty(0, np.int64)
    else:
        starts = np.arange(graph.n_nodes, dtype=np.int64)
    return np.repeat(starts, walks_per_node)


def _walk_block(graph: ArcGraph, block: np.ndarray, length: int, rng: np.random.Generator) -> np.ndarray:
    """One uniform draw per active walk per step; -1 pads only walks started at isolated nodes."""
    degree = graph.degree
    walks = np.full((block.size, length), -1, dtype=np.int64)
    walks[:, 0] = block
    current = block.copy()
    active = degree[current] > 0
    for step in range(1, length):
        if not active.any():
            break
        cur = current[active]
        draw = rng.random(cur.size)
        nxt = graph.indices[graph.indptr[cur] + np.minimum((draw * degree[cur]).astype(np.int64), degree[cur] - 1)]
        walks[active, step] = nxt
        current[active] = nxt
    return walks


def walk_chunks(graph: ArcGraph, walks_per_node: int = WALKS_PER_NODE, length: int = WALK_LENGTH, seed: int = SEED,
                high_degree: bool = False, chunk: int = WALK_CHUNK) -> Iterator[np.ndarray]:
    """Walks in blocks, each from its own default_rng([seed, index]): deterministic in (seed, chunk)."""
    if length < 1 or walks_per_node < 1 or chunk < 1:
        raise ValueError("walks_per_node, length and chunk must be positive")
    starts = walk_starts(graph, walks_per_node, high_degree)
    for index, begin in enumerate(range(0, starts.size, chunk)):
        yield _walk_block(graph, starts[begin:begin + chunk], length, np.random.default_rng([seed, index]))


class WalkCorpus:
    """Re-iterable token corpus for gensim; regenerates the walks per pass instead of storing them."""

    def __init__(self, graph: ArcGraph, walks_per_node: int = WALKS_PER_NODE, length: int = WALK_LENGTH,
                 seed: int = SEED, high_degree: bool = False, chunk: int = WALK_CHUNK):
        self._graph = graph
        self._kwargs = {"walks_per_node": walks_per_node, "length": length, "seed": seed,
                        "high_degree": high_degree, "chunk": chunk}
        self._tokens = [str(i) for i in range(graph.n_nodes)]

    def __iter__(self) -> Iterator[list[str]]:
        tokens = self._tokens
        for block in walk_chunks(self._graph, **self._kwargs):
            for walk in block:
                row = walk[walk >= 0]
                if row.size:
                    yield [tokens[i] for i in row.tolist()]


def stable_hash(token: str) -> int:
    """gensim seeds word vectors from hash(word); Python salts str hashes per process, SHA-256 does not."""
    return int.from_bytes(hashlib.sha256(token.encode()).digest()[:7], "big")


def train_embeddings(corpus: WalkCorpus, dimensions: int = DIMENSIONS, window: int = WINDOW, epochs: int = EPOCHS,
                     seed: int = SEED, workers: int = 1):
    from gensim.models import Word2Vec
    return Word2Vec(sentences=corpus, vector_size=dimensions, window=window, sg=SKIP_GRAM, negative=NEGATIVE,
                    min_count=MIN_COUNT, epochs=epochs, seed=seed, workers=workers, hashfxn=stable_hash)


def column_vectors(model, nodes_per_column: Sequence[Sequence[int]], dimensions: int = DIMENSIONS) -> np.ndarray:
    """Mean of the distinct cell-value embeddings; an all-null column gets the zero vector."""
    key = model.wv
    out = np.zeros((len(nodes_per_column), dimensions), dtype=np.float64)
    for j, nodes in enumerate(nodes_per_column):
        vectors = [key[str(node)] for node in nodes if str(node) in key]
        if vectors:
            out[j] = np.mean(np.asarray(vectors, dtype=np.float64), axis=0)
    return out


def cosine_scores(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    """Raw cosine in [-1, 1], not clipped (clipping would turn every negative cell into an exact tie)."""
    ln = np.linalg.norm(left, axis=1, keepdims=True)
    rn = np.linalg.norm(right, axis=1, keepdims=True)
    left = left / np.where(ln == 0, 1, ln)
    right = right / np.where(rn == 0, 1, rn)
    return left @ right.T


def rema_arc(source, target, row_cap: int = ROW_CAP, seed: int = SEED, epochs: int = EPOCHS, workers: int = 1,
             high_degree: bool = False, dimensions: int = DIMENSIONS) -> np.ndarray:
    graph = build_arc_graph(source, target, row_cap=row_cap)
    model = train_embeddings(WalkCorpus(graph, seed=seed, high_degree=high_degree), dimensions=dimensions,
                             epochs=epochs, seed=seed, workers=workers)
    return cosine_scores(column_vectors(model, graph.value_nodes[0], dimensions),
                         column_vectors(model, graph.value_nodes[1], dimensions))


def read_columns(path: Path) -> tuple[list[str], list[list[str]]]:
    """Header and column-major values of one CSV, all rows; ragged rows are an error."""
    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.reader(handle)
        header = next(reader, [])
        columns = [[] for _ in header]
        for values in reader:
            if len(values) != len(header):
                raise ValueError(f"Ragged table: {path}")
            for column, value in zip(columns, values):
                column.append(value)
    return header, columns


def rema(ctx, options) -> np.ndarray:
    """Study-server entry point: one table per side; the graph follows each table's own column order, and the result is
    reordered to ctx["rows"] / ctx["cols"], so it does not depend on the order in which Schematch lists the columns."""
    sides = []
    for side, labels in (("source", ctx["rows"]), ("target", ctx["cols"])):
        files = sorted((Path(ctx["rt"]) / ctx["ds"] / ctx["sc"] / side).glob("*.csv"))
        if len(files) != 1:
            raise ValueError(f"REMA matches two relations; {side} has {len(files)} tables")
        header, columns = read_columns(files[0])
        position = {f"{files[0].stem}.{h}": j for j, h in enumerate(header)}
        missing = [l for l in labels if l not in position]
        if missing:
            raise ValueError(f"labels not in the {side} table: {missing[:3]}")
        sides.append((columns, [position[l] for l in labels]))
    S = rema_arc(sides[0][0], sides[1][0], row_cap=int(options.get("row_cap", ROW_CAP)),
                 seed=int(options.get("seed", SEED)), epochs=int(options.get("epochs", EPOCHS)),
                 high_degree=bool(options.get("high_degree", False)),
                 dimensions=int(options.get("dimensions", DIMENSIONS)))
    return S[np.ix_(sides[0][1], sides[1][1])]
