"""Table-pair matcher server for Schematch.

Built-in numerical methods use schematch_py in this directory. Optional matchers
use installed Python packages and explicitly configured interpreters/resources.
No repository or environment is discovered outside the Schematch project.
Run: python src/main/resources/python_matchers/seed_server.py --port 5101
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import sys
import traceback
from functools import lru_cache
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

MAX_ROWS = 1000
RANDOM_CONTROL_SEED = 20260908                       # original embedding baseline


# ---------------------------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------------------------

import re  # noqa: E402

# A numeric literal whose exponent does not fit a 32-bit int (MD5 digests such as
# "35079e54619883063cd..."). pandas' C float parser accumulates the exponent with overflow:
# undefined behaviour that crashes on Linux and silently yields 0.0 on Windows.
_HUGE_EXPONENT_FIELD = re.compile(rb'(?:^|[,"\n])[+-]?(?:\d+\.?\d*|\.\d+)[eE][+-]?\d{10,}')
_HUGE_EXPONENT_VALUE = re.compile(r"\s*[+-]?(?:\d+\.?\d*|\.\d+)[eE][+-]?\d{10,}")


def read_table(path: str, max_rows: int, as_text: bool = False) -> pd.DataFrame:
    """pandas' default parsing, as the reference configuration reads tables for Valentine and Magneto; or raw
    text cells (for serialising sample values into an embedding input).

    A table containing a huge-exponent token is read with the round-trip (Python) float parser,
    which handles it; every other table keeps the default parser, so its parse is unchanged.
    """
    nrows = max_rows if max_rows > 0 else None
    if as_text:
        return pd.read_csv(path, nrows=nrows, low_memory=False, dtype=str, keep_default_na=False)
    with open(path, "rb") as fh:
        risky = _HUGE_EXPONENT_FIELD.search(fh.read()) is not None
    if risky:
        return pd.read_csv(path, nrows=nrows, low_memory=False, float_precision="round_trip")
    return pd.read_csv(path, nrows=nrows, low_memory=False)


def _safe_huge_exponents(values):
    """Strings with a huge exponent get Python's float semantics before pandas' C parser sees
    them: a full literal becomes float(s) (overflow -> NaN, as pandas reports 1e400; underflow
    -> 0.0), a literal followed by other characters is not a number (NaN)."""
    def fix(v):
        if isinstance(v, str):
            m = _HUGE_EXPONENT_VALUE.match(v)
            if m:
                if m.end() == len(v.rstrip()):
                    f = float(v)
                    return np.nan if np.isinf(f) else f
                return np.nan
        return v
    if isinstance(values, pd.Series) and values.dtype == object:
        return values.map(fix)
    if isinstance(values, (list, tuple, np.ndarray)) and (not isinstance(values, np.ndarray) or values.dtype == object):
        return type(values)(fix(v) for v in values) if not isinstance(values, np.ndarray) else np.array([fix(v) for v in values], dtype=object)
    return fix(values) if isinstance(values, str) else values


def patch_for_magneto() -> None:
    """Make Magneto's output independent of the CPU and safe on MD5 values.

    1. Its default 'mixed' sampling takes value_counts().head(n), whose order among equally
       frequent values comes from an unstable sort; numpy's sort dispatches on the CPU
       (AVX-512 vs AVX2), so ties came out in different orders on the workstation and the
       servers and Magneto sampled different values. Ties are now broken by first occurrence.
    2. pd.to_numeric (column-type detection) crashes on huge-exponent strings on Linux; they are
       converted with Python's semantics first (see _safe_huge_exponents).
    """
    import magneto.utils.utils as mu
    original = mu.get_samples

    def get_samples(values, n=15, mode="priority_sampling"):
        if mode != "mixed":
            return original(values, n=n, mode=mode)
        unique_values = values.dropna().unique()
        total_unique = len(unique_values)
        if total_unique <= n:
            return sorted([str(val) for val in unique_values])
        n_frequent = n // 2
        counts = values.dropna().value_counts(sort=False)          # first-occurrence order
        counts = counts.iloc[np.argsort(-counts.to_numpy(), kind="stable")]
        most_frequent_values = counts.head(n_frequent).index.tolist()
        n_diverse = n - n_frequent
        spacing_interval = max(1, total_unique // n_diverse)
        diverse_values = unique_values[::spacing_interval][:n_diverse]
        return sorted(set(map(str, most_frequent_values + list(diverse_values))))

    import magneto.basic_matcher, magneto.column_encoder, magneto.embedding_matcher, magneto.magneto  # noqa: E401
    for module in (mu, magneto.basic_matcher, magneto.column_encoder, magneto.embedding_matcher, magneto.magneto):
        if hasattr(module, "get_samples"):
            module.get_samples = get_samples

    # 3. bdikit caches rank_schema_matches results under ~/.cache/bdikit/runs, keyed by the input
    #    only; a cached result would bypass the patches above (and differs between machines).
    import bdikit.api as ba
    ba.load_from_cache = lambda *a, **k: None
    ba.save_in_cache = lambda *a, **k: None

    to_numeric = pd.to_numeric
    if not getattr(to_numeric, "_smx_safe", False):
        def safe_to_numeric(arg, *args, **kwargs):
            return to_numeric(_safe_huge_exponents(arg), *args, **kwargs)
        safe_to_numeric._smx_safe = True
        pd.to_numeric = safe_to_numeric


def cosine(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    ln = np.linalg.norm(left, axis=1, keepdims=True)
    rn = np.linalg.norm(right, axis=1, keepdims=True)
    left = left / np.where(ln == 0, 1, ln)
    right = right / np.where(rn == 0, 1, rn)
    return np.clip(left @ right.T, 0.0, 1.0)


def from_pairs(pairs: dict, s_cols: list[str], t_cols: list[str]) -> np.ndarray:
    """Dense matrix from Valentine's {ColumnPair: score}; absent pairs are 0."""
    s_index = {c: i for i, c in enumerate(s_cols)}
    t_index = {c: j for j, c in enumerate(t_cols)}
    S = np.zeros((len(s_cols), len(t_cols)))
    for pair, score in pairs.items():
        i, j = s_index.get(pair.source_column), t_index.get(pair.target_column)
        if i is not None and j is not None:
            S[i, j] = float(score)
    return S


@lru_cache(maxsize=1)
def valentine():
    from importlib.metadata import version, PackageNotFoundError
    try:
        installed = version("valentine")
    except PackageNotFoundError as exc:
        raise RuntimeError("Install requirements/valentine.txt in the seed server environment") from exc
    if installed != "1.0.0":
        raise RuntimeError(f"Valentine 1.0.0 is required for these adapters; found {installed}. "
                           "Use a separate environment with requirements/valentine.txt.")
    import valentine as module
    # Resource installation is explicit; the matching server never downloads NLTK data.
    import nltk
    def cached_resource(resource, *args, **kwargs):
        location = "tokenizers/" if resource == "punkt_tab" else "corpora/"
        for path in (location + resource, location + resource + ".zip"):
            try:
                nltk.data.find(path)
                return True
            except LookupError:
                pass
        raise LookupError("Missing local NLTK resource " + str(resource) +
                          "; install the resources listed in docs/python-matchers.md before matching")
    nltk.download = cached_resource
    return module



def valentine_tables(request: dict, options: dict):
    valentine()
    from valentine.data_sources import DataframeTable
    max_rows = int(options.get("max_rows", MAX_ROWS))
    source = read_table(request["source_path"], max_rows)
    target = read_table(request["target_path"], max_rows)
    s = DataframeTable(source, name="source", instance_sample_size=None)
    t = DataframeTable(target, name="target", instance_sample_size=None)
    return source, target, s, t


@lru_cache(maxsize=4)
def encoder(name: str):
    from sentence_transformers import SentenceTransformer
    return SentenceTransformer(name)


# ---------------------------------------------------------------------------------------------
# methods
# ---------------------------------------------------------------------------------------------

def m_sbert(request: dict, options: dict) -> np.ndarray:
    model = encoder(options.get("model", "all-MiniLM-L6-v2"))
    s_text, t_text = list(request["source_labels"]), list(request["target_labels"])
    if options.get("text", "label") == "label_values":
        k = int(options.get("values", 5))
        max_rows = int(options.get("max_rows", MAX_ROWS))
        for texts, path in ((s_text, request["source_path"]), (t_text, request["target_path"])):
            frame = read_table(path, max_rows, as_text=True)
            for n, col in enumerate(frame.columns):
                vals = [v for v in dict.fromkeys(frame[col].tolist()) if str(v).strip()][:k]
                texts[n] = f"{texts[n]}: " + ", ".join(map(str, vals))
    prefix = options.get("prefix", "")  # e5 models expect "query: "
    left = np.asarray(model.encode([prefix + t for t in s_text], normalize_embeddings=True))
    right = np.asarray(model.encode([prefix + t for t in t_text], normalize_embeddings=True))
    return cosine(left, right)


def random_vectors(labels: list[str], dim: int) -> np.ndarray:
    out = np.empty((len(labels), dim))
    for i, label in enumerate(labels):
        digest = hashlib.sha256(f"{RANDOM_CONTROL_SEED}|{label}".encode()).digest()
        out[i] = np.random.default_rng(int.from_bytes(digest[:8], "little")).normal(size=dim)
    return out


def m_embedding_random(request: dict, options: dict) -> np.ndarray:
    dim = int(options.get("dim", 384))
    return cosine(random_vectors(request["source_labels"], dim),
                  random_vectors(request["target_labels"], dim))


def m_valentine_distribution(request: dict, options: dict) -> np.ndarray:
    source, target, s, t = valentine_tables(request, options)
    from valentine.algorithms import DistributionBased
    matcher = DistributionBased(threshold1=float(options.get("threshold1", 0.15)),
                                threshold2=float(options.get("threshold2", 0.15)))
    return from_pairs(matcher.get_matches(s, t), list(source.columns), list(target.columns))


def m_valentine_jaccard(request: dict, options: dict) -> np.ndarray:
    source, target, s, t = valentine_tables(request, options)
    from valentine.algorithms import JaccardDistanceMatcher
    from valentine.algorithms.jaccard_distance import StringDistanceFunction
    fun = getattr(StringDistanceFunction, options.get("distance_fun", "Levenshtein"))
    matcher = JaccardDistanceMatcher(threshold_dist=float(options.get("threshold_dist", 0.8)),
                                     distance_fun=fun)
    return from_pairs(matcher.get_matches(s, t), list(source.columns), list(target.columns))


@lru_cache(maxsize=1)
def dense_runner():
    valentine()
    from schematch_py import valentine_dense
    return valentine_dense



def m_coma(request: dict, options: dict, instances: bool = False) -> np.ndarray:
    source, target, s, t = valentine_tables(request, options)
    vmc = dense_runner()
    S, _ = vmc.run_coma(s, t, list(source.columns), list(target.columns),
                        dict(use_schema=True, use_instances=instances), False)
    return S


def m_cupid(request: dict, options: dict) -> np.ndarray:
    source, target, s, t = valentine_tables(request, options)
    vmc = dense_runner()
    S, _ = vmc.run_cupid(s, t, list(source.columns), list(target.columns), {}, False)
    return S


_MAGNETO_POOL = None


def m_magneto(request: dict, options: dict) -> np.ndarray:
    """Magneto in a separate, spawned process.

    Magneto (via bdikit) is written against the Valentine 0.5 package in site-packages, whose
    MatcherResults is a dict; the Valentine methods here import the 1.0 checkout, whose
    MatcherResults is not. Both cannot live in one interpreter, so Magneto gets its own, kept
    alive between requests so the model loads once.
    """
    interpreter = options.get("python") or os.environ.get("SCHEMATCH_MAGNETO_PYTHON")
    if interpreter:
        import subprocess
        environment = {key:value for key,value in os.environ.items() if key != "PYTHONPATH"}
        done = subprocess.run([str(interpreter), str(Path(__file__).with_name("backend_worker.py"))],
                              input=json.dumps({"backend":"magneto","request":request,"options":options}),
                              text=True, capture_output=True, env=environment,
                              cwd=Path(__file__).resolve().parent, timeout=int(options.get("timeout",3600)))
        if done.returncode:
            raise RuntimeError("Installed Magneto backend failed: " + done.stderr[-2000:])
        return np.asarray(json.loads(done.stdout),dtype=float)
    global _MAGNETO_POOL
    if _MAGNETO_POOL is None:
        import multiprocessing as mp
        from concurrent.futures import ProcessPoolExecutor
        _MAGNETO_POOL = ProcessPoolExecutor(max_workers=1, mp_context=mp.get_context("spawn"))
    return _MAGNETO_POOL.submit(magneto_in_process, request, options).result()


def magneto_in_process(request: dict, options: dict) -> np.ndarray:
    from bdikit.api import rank_schema_matches
    patch_for_magneto()
    max_rows = int(options.get("max_rows", MAX_ROWS))
    source = read_table(request["source_path"], max_rows)
    target = read_table(request["target_path"], max_rows)
    variant = options.get("variant")
    if variant:
        ranked = bdikit_variant(variant, source, target, options)
    else:
        ranked = rank_schema_matches(source, target=target, top_k=max(1, target.shape[1]),
                                     method=options.get("method", "magneto_zs_bp"))
    s_index = {c: i for i, c in enumerate(source.columns)}
    t_index = {c: j for j, c in enumerate(target.columns)}
    S = np.zeros((source.shape[1], target.shape[1]))
    for _, r in ranked.iterrows():
        i, j = s_index.get(r.iloc[0]), t_index.get(r.iloc[1])
        if i is not None and j is not None:
            S[i, j] = float(r.iloc[2])
    return S


def bdikit_variant(variant: str, source, target, options: dict):
    """bdikit's composite matchers with the zero-shot Magneto (MagnetoZSBP) as the top-k stage. bdikit's default,
    MagnetoFTBP, is fine-tuned on GDC and would leak into GDC-SM. max_val_sim re-scores the top-k candidates with
    contribution_factor * TF-IDF value similarity + (1 - contribution_factor) * Magneto; two_phase runs a 1:1 schema matcher
    (SimFlood by default, or COMA / Cupid) on each source column against its top-k candidates."""
    from bdikit.api import match_schema, rank_schema_matches
    from bdikit.schema_matching.magneto import MagnetoZSBP
    top_k = int(options.get("top_k", 20))
    if variant == "max_val_sim":
        from bdikit.schema_matching.maxvalsim import MaxValSim
        matcher = MaxValSim(top_k=top_k, contribution_factor=float(options.get("contribution_factor", 0.5)), top_k_matcher=MagnetoZSBP())
        return rank_schema_matches(source, target=target, top_k=max(1, target.shape[1]), method=matcher, use_cache=False)
    if variant == "two_phase":
        from bdikit.schema_matching import valentine as bv
        from bdikit.schema_matching.twophase import TwoPhase
        second = {"simflood": bv.SimFlood, "coma": bv.Coma, "cupid": bv.Cupid}[options.get("second_stage", "simflood")]()
        return match_schema(source, target=target, method=TwoPhase(top_k=top_k, top_k_matcher=MagnetoZSBP(), schema_matcher=second),
                            use_cache=False)
    raise KeyError(f"unknown bdikit variant {variant!r}")


# ---------------------------------------------------------------------------------------------
# second layer
# ---------------------------------------------------------------------------------------------

ISRESMAT_PYTHON = os.environ.get("SCHEMATCH_ISRESMAT_PYTHON", sys.executable)
ISRESMAT_LOG = Path(os.environ.get("SCHEMATCH_LOG_DIR", "logs")) / "isresmat_attempts.jsonl"


def log_isresmat_attempt(record: dict) -> None:
    record["time"] = __import__("time").strftime("%Y-%m-%d %H:%M:%S")
    sys.stderr.write(f"isresmat attempt: {json.dumps(record)[:400]}\n")
    if ISRESMAT_LOG.parent.is_dir():
        with ISRESMAT_LOG.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(record) + "\n")


def m_isresmat(request: dict, options: dict) -> np.ndarray:
    """ISResMat in its own interpreter (Python 3.12 + torch), via the vendored CLI's
    --export-matrix. Columns ISResMat drops (entirely empty) stay at 0."""
    import csv
    import subprocess
    import tempfile

    import shutil
    interpreter = str(options.get("python", ISRESMAT_PYTHON))
    if not shutil.which(interpreter) and not Path(interpreter).is_file():
        raise FileNotFoundError("ISResMat requires an installed compatible CLI; configure options.python")
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "matrix.csv"
        cmd = [interpreter, "-m", str(options.get("module", "isresmat")),
               f"--n-trn-cols={int(options.get('n_trn_cols', 200))}", "--batch-size=1",
               "--frag-height=6", "--frag-width=12", "--learning-rate=3e-5",
               f"--col-name-prob={float(options.get('col_name_prob', 0))}",
               "--store-matches=0", "--num-workers=0", "--comment=seed_server",
               f"--dataset-name=seed_server/{request['source_table']}___{request['target_table']}",
               f"--orig-file-src={request['source_path']}", f"--orig-file-tgt={request['target_path']}",
               f"--export-matrix={out}"]
        if "seed" in options:
            cmd.append(f"--seed={int(options['seed'])}")
        # ISResMat opens the CSVs with the platform default encoding (cp1252 on Windows), which
        # fails on UTF-8 tables; Python's UTF-8 mode fixes that without touching its code.
        env = {key: value for key, value in os.environ.items() if key != "PYTHONPATH"}
        env["PYTHONUTF8"] = "1"
        # The GPU is also the desktop's; a driver reset surfaces as "CUDA error: unknown error"
        # mid-training. Retry on the GPU, then once on the CPU. Every attempt is logged, so the
        # device that produced each matrix is on record.
        gpu_attempts = int(options.get("gpu_attempts", 3))
        attempts = [("cuda", env)] * gpu_attempts + [("cpu", {**env, "CUDA_VISIBLE_DEVICES": ""})]
        done = None
        for k, (device, attempt_env) in enumerate(attempts, 1):
            if out.exists():
                out.unlink()
            t0 = __import__("time").time()
            done = subprocess.run(cmd, cwd=tmp, capture_output=True, text=True, encoding="utf-8",
                                  errors="replace", env=attempt_env, timeout=int(options.get("timeout", 3600)))
            ok = done.returncode == 0 and out.is_file()
            log_isresmat_attempt({"pair": f"{request['source_path']} | {request['target_path']}",
                                  "options": options, "attempt": k, "device": device,
                                  "exit": done.returncode, "ok": ok,
                                  "seconds": round(__import__("time").time() - t0, 1),
                                  "error_tail": "" if ok else done.stderr[-300:]})
            if ok:
                break
        if done is None or done.returncode != 0 or not out.is_file():
            raise RuntimeError(f"ISResMat failed after {len(attempts)} attempts (last exit "
                               f"{done.returncode}): {done.stderr[-2000:]}")
        with out.open(newline="", encoding="utf-8") as fh:
            table = list(csv.reader(fh))
        cols = table[0][1:]
        rows = [r[0] for r in table[1:]]
        S = np.asarray([[float(v) for v in r[1:]] for r in table[1:]])
    s_index = {c: i for i, c in enumerate(request["source_labels"])}
    t_index = {c: j for j, c in enumerate(request["target_labels"])}
    full = np.zeros((len(s_index), len(t_index)))
    for a, label_a in enumerate(rows):
        for b, label_b in enumerate(cols):
            i, j = s_index.get(label_a), t_index.get(label_b)
            if i is not None and j is not None:
                full[i, j] = (S[a, b] + 1.0) / 2.0
    return full


TOKEN = None


def label_tokens(label: str) -> list[str]:
    """Valentine's camelCase / snake_case split, as LabelNormalizer.SPLIT in Java."""
    import re
    global TOKEN
    if TOKEN is None:
        TOKEN = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\d+")
    return TOKEN.findall(label) or ([label] if label else [])


@lru_cache(maxsize=2)
def load_vectors(path: str) -> tuple[dict, int]:
    vectors, dim = {}, 0
    with open(path, encoding="utf-8", errors="ignore") as fh:
        for n, line in enumerate(fh):
            parts = line.rstrip().split(" ")
            if n == 0 and len(parts) == 2 and all(p.isdigit() for p in parts):
                continue  # fastText .vec header: "<count> <dim>"
            if len(parts) < 3:
                continue
            try:
                vec = np.asarray(parts[1:], dtype=np.float32)
            except ValueError:
                continue
            dim = dim or len(vec)
            if len(vec) == dim:
                vectors[parts[0]] = vec
    if not vectors:
        raise ValueError(f"no vectors read from {path}")
    return vectors, dim


def m_word_vectors(request: dict, options: dict) -> np.ndarray:
    path = options.get("vectors")
    if not path or not Path(path).is_file():
        raise FileNotFoundError("word_vectors needs options.vectors = a local fastText/GloVe/word2vec "
                                "text file; none is installed and nothing is downloaded")
    vectors, dim = load_vectors(path)
    lower = bool(options.get("lowercase", True))

    def embed(labels):
        out = np.zeros((len(labels), dim))
        for i, label in enumerate(labels):
            toks = [t.lower() if lower else t for t in label_tokens(label)]
            found = [vectors[t] for t in toks if t in vectors]
            if found:
                out[i] = np.mean(found, axis=0)
        return out

    return cosine(embed(request["source_labels"]), embed(request["target_labels"]))


def text_columns(path: str, max_rows: int) -> list[list[str]]:
    frame = read_table(path, max_rows, as_text=True)
    return [frame[c].tolist() for c in frame.columns]


@lru_cache(maxsize=8)
def bundled_module(name: str):
    import importlib
    return importlib.import_module("schematch_py." + name)



def m_kang_naughton(request: dict, options: dict, profile: bool = False) -> np.ndarray:
    kn = bundled_module("kang_naughton")
    max_rows = int(options.get("max_rows", 0))  # KN reads whole tables in the reference configuration
    w1 = kn.mi_matrix(text_columns(request["source_path"], max_rows))
    w2 = kn.mi_matrix(text_columns(request["target_path"], max_rows))
    return kn.kn_profile_scores(w1, w2) if profile else kn.kn_graph_scores(w1, w2)


def m_soft_overlap(request: dict, options: dict, kind: str) -> np.ndarray:
    so = bundled_module("soft_overlap")
    cap, seed = int(options.get("cap", 128)), int(options.get("seed", 20260903))
    max_rows = int(options.get("max_rows", 0))
    samples = []
    for path in (request["source_path"], request["target_path"]):
        side = []
        for col in text_columns(path, max_rows):
            non_null = [v for v in col if v.strip()]
            normalised = [v for v in sorted({so.normalise(x) for x in non_null}) if v]
            side.append(so.sample_values(normalised, cap, seed))
        samples.append(side)
    return so.fuzzy_matrix(*samples, kind=kind)


def m_bdikit_cl(request: dict, options: dict) -> np.ndarray:
    """bdi-kit's contrastive-learning matcher (ct_learning, bdi-cl-v0.2), via schematch_py.bdikit_cl without bdi-kit."""
    max_rows = int(options.get("max_rows", MAX_ROWS))
    return bundled_module("bdikit_cl").scores(read_table(request["source_path"], max_rows),
                                              read_table(request["target_path"], max_rows), options)


def m_snoopy(request: dict, options: dict) -> np.ndarray:
    """Snoopy's released proxy columns over fastText cell vectors (schematch_py.snoopy)."""
    max_rows = int(options.get("max_rows", 0))
    return bundled_module("snoopy").scores(text_columns(request["source_path"], max_rows),
                                           text_columns(request["target_path"], max_rows), options)


def m_simple(request: dict, options: dict, name: str) -> np.ndarray:
    """The simple label / value / record measures of schematch_py.simple_matchers (no model, no download)."""
    sm = bundled_module("simple_matchers")
    if name in sm.LABEL_METHODS:
        return sm.LABEL_METHODS[name](request["source_labels"], request["target_labels"])
    max_rows = int(options.get("max_rows", 0))
    return sm.VALUE_METHODS[name](text_columns(request["source_path"], max_rows), text_columns(request["target_path"], max_rows))



def m_role(request: dict, options: dict, relation: str) -> np.ndarray:
    """Within-table relational roles: each column is
    described by its role in a relation among its own table's columns, and columns are compared
    by the 12-D role descriptor's 1 - mean L1 distance. Opaque to labels and to value recoding.

    relation: gcov (chance-corrected within-table inclusion), gcov_sig (gcov with its binomial
    significance, the `+sig` variant), uncertainty (U(Y|X) on pair-complete rows).
    """
    rr = bundled_module("relational_roles")
    efa = bundled_module("role_features")
    max_rows = int(options.get("max_rows", 0))  # the reference configuration reads whole tables
    profiles = []
    for path in (request["source_path"], request["target_path"]):
        columns = text_columns(path, max_rows)
        if relation == "gcov_sig":
            clean = [[v for v in col if v is not None] for col in rr.aligned_values(columns)]
            gcov, significance = efa.within_ind_evidence(clean)
            profiles.append(efa.within_ind_role_profile(gcov, significance))
        else:
            relations, _, _ = rr.relation_matrices(columns)
            profiles.append(efa.within_ind_role_profile(relations[relation]))
    return efa.vector_similarity(*profiles)


def m_semantic_types(request: dict, options: dict) -> np.ndarray:
    spec = options.get("adapter")
    if not spec or ":" not in spec:
        raise ValueError("semantic_types needs options.adapter = 'module.path:function' "
                         "(function(columns: list[list[str]]) -> n_columns x n_types probabilities). "
                         "No Sherlock/Sato/DoDuo model is installed, so there is no default.")
    import importlib
    module, function = spec.split(":", 1)
    predict = getattr(importlib.import_module(module), function)
    max_rows = int(options.get("max_rows", MAX_ROWS))
    left = np.asarray(predict(text_columns(request["source_path"], max_rows)), dtype=float)
    right = np.asarray(predict(text_columns(request["target_path"], max_rows)), dtype=float)
    return cosine(left, right)


LLM_PROMPT = """You are matching the columns of two database tables.

Source table "{source}" columns:
{source_cols}

Target table "{target}" columns:
{target_cols}

For each listed (source column, target column) pair, give the probability in [0, 1] that the two
columns hold the same real-world attribute, judging from the names and the sample values.
Pairs to score:
{pairs}

Return every listed pair exactly once."""


def m_llm(request: dict, options: dict) -> np.ndarray:
    if os.environ.get("SMX_ALLOW_LLM") != "1":
        raise PermissionError("the llm method sends column names and sample values to the Anthropic "
                              "API and costs money; it runs only when the server is started with "
                              "SMX_ALLOW_LLM=1")
    import anthropic

    schema = {
        "type": "object",
        "properties": {
            "scores": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "source": {"type": "string"},
                        "target": {"type": "string"},
                        "score": {"type": "number"},
                    },
                    "required": ["source", "target", "score"],
                    "additionalProperties": False,
                },
            },
        },
        "required": ["scores"],
        "additionalProperties": False,
    }

    s_labels, t_labels = list(request["source_labels"]), list(request["target_labels"])
    k = int(options.get("values", 5))
    max_rows = int(options.get("max_rows", MAX_ROWS))

    def describe(path, labels):
        lines = []
        for label, col in zip(labels, text_columns(path, max_rows)):
            vals = [v for v in dict.fromkeys(col) if v.strip()][:k]
            lines.append(f"- {label}: " + ", ".join(vals))
        return "\n".join(lines)

    candidates = int(options.get("candidates", 0))
    if candidates > 0:
        retrieval = m_sbert(request, {"model": options.get("retriever", "all-mpnet-base-v2"),
                                      "text": "label_values"})
        pairs = [(i, j) for i in range(len(s_labels))
                 for j in np.argsort(-retrieval[i])[:candidates]]
    else:
        pairs = [(i, j) for i in range(len(s_labels)) for j in range(len(t_labels))]

    prompt = LLM_PROMPT.format(
        source=request["source_table"], target=request["target_table"],
        source_cols=describe(request["source_path"], s_labels),
        target_cols=describe(request["target_path"], t_labels),
        pairs="\n".join(f"- {s_labels[i]} | {t_labels[j]}" for i, j in pairs))

    # Credentials come from the environment (ANTHROPIC_API_KEY or an `ant auth login` profile).
    client = anthropic.Anthropic()
    response = client.beta.messages.create(
        model=options.get("model", "claude-opus-5-5"),
        max_tokens=16000,
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
        output_config={"effort": options.get("effort", "medium"),
                       "format": {"type": "json_schema", "schema": schema}},
        messages=[{"role": "user", "content": prompt}],
    )
    if response.stop_reason == "refusal":
        raise RuntimeError(f"the model declined the request: {response.stop_details}")
    if response.stop_reason == "max_tokens":
        raise RuntimeError("the response hit max_tokens; use candidates > 0 for wide tables")
    text = next(b.text for b in response.content if b.type == "text")
    s_index = {c: i for i, c in enumerate(s_labels)}
    t_index = {c: j for j, c in enumerate(t_labels)}
    S = np.zeros((len(s_labels), len(t_labels)))
    for p in json.loads(text)["scores"]:
        i, j = s_index.get(p["source"]), t_index.get(p["target"])
        if i is not None and j is not None:
            S[i, j] = min(1.0, max(0.0, float(p["score"])))
    return S



# --- harness channels, bundled verbatim (schematch_py/SOURCES.json); input adaptation only below -------------------------
# A table at <scenario>/<side>/<table>.csv has its profiles at <scenario>/metadata/<side>/<table>/ (the prepared layout).
def table_metadata(path: str, filename: str) -> Path:
    p = Path(path)
    return p.parent.parent / "metadata" / p.parent.name / p.stem / filename


def m_lex_pmf(request: dict, options: dict) -> np.ndarray:
    """LEX+PMF: the harness's frozen seed, scale_aligned_fuse(Levenshtein labels, PMF, w), w = 0.35 by default. The
    Levenshtein matrix is the Python port of the Java measure (float32; regime_tables.label_matrix), so a 1-ULP tie can
    differ from the Java LevenshteinMatcher on a few scenarios."""
    rf, lev = bundled_module("role_features"), bundled_module("label_lev")
    sh, sc = rf.read_table(Path(request["source_path"]))
    th, tc = rf.read_table(Path(request["target_path"]))
    lex = lev.label_matrix(sh, th).astype(float)
    return rf.scale_aligned_fuse(lex, rf.pmf_scores(sc, tc), float(options.get("weight", 0.35)))


def m_unary_key_role(request: dict, options: dict) -> np.ndarray:
    """Unary-key role: 1 if both or neither column is a single-column UCC of its table (UCC_results.txt), else 0."""
    rf = bundled_module("role_features")
    flags = []
    for side in ("source", "target"):
        header, _ = rf.read_table(Path(request[f"{side}_path"]))
        keys = rf.read_uccs(table_metadata(request[f"{side}_path"], "UCC_results.txt"))
        flags.append(rf.unary_ucc_flags(keys, header))
    return rf.scalar_similarity(*flags)


def _value_sets(path: str):
    an, ag = bundled_module("anchors"), bundled_module("anchor_generalization")
    values = an.column_values(Path(path))
    return {ag.bare(k): v for k, v in values.items()}, [ag.bare(k) for k in values]


def m_gcov_pind(request: dict, options: dict) -> np.ndarray:
    """Cross-schema partial-IND coverage (anchor_matrix): variant gcov_both (default; chance-corrected both directions),
    gcov, cov, both or both_card, against the pool of distinct values of both tables."""
    ag = bundled_module("anchor_generalization")
    sv, s_cols = _value_sets(request["source_path"])
    tv, t_cols = _value_sets(request["target_path"])
    return ag.anchor_matrix(sv, tv, s_cols, t_cols, options.get("variant", "gcov_both"))


def m_cross_ind(request: dict, options: dict) -> np.ndarray:
    """Cross-schema IND channels (harness/cross_schema_ind.py `channels`): channel I_g (default; chance-corrected partial
    IND in either direction), E_g (both directions), I_raw, E_raw (uncorrected) or X (an exact unary IND)."""
    channel = options.get("channel", "I_g")
    if channel not in ("I_g", "E_g", "I_raw", "E_raw", "X"):
        raise ValueError(f"cross_ind channel must be I_g, E_g, I_raw, E_raw or X: {channel}")
    sv, s_cols = _value_sets(request["source_path"])
    tv, t_cols = _value_sets(request["target_path"])
    S = [sv.get(c, set()) for c in s_cols]
    T = [tv.get(c, set()) for c in t_cols]
    n = len(set().union(*sv.values(), *tv.values())) if sv and tv else 0
    out = np.zeros((len(S), len(T)))
    for i, a in enumerate(S):
        for j, b in enumerate(T):
            if not a or not b:
                continue
            k = len(a & b)
            if k == 0:
                continue
            cab, cba = k / len(a), k / len(b)
            bab, bba = min(len(b) / n, 1.0), min(len(a) / n, 1.0)
            gab = (cab - bab) / (1 - bab) if bab < 1 else 0.0
            gba = (cba - bba) / (1 - bba) if bba < 1 else 0.0
            out[i, j] = {"I_g": max(0.0, gab, gba), "E_g": max(0.0, min(gab, gba)), "I_raw": max(cab, cba),
                         "E_raw": min(cab, cba), "X": 1.0 if cab == 1.0 or cba == 1.0 else 0.0}[channel]
    return out


def _hmac_sets(path: str):
    rf = bundled_module("role_features")
    _, columns = rf.read_table(Path(path))
    return rf.shared_hmac_sets(columns)


def m_hmac_containment(request: dict, options: dict) -> np.ndarray:
    """Shared-key HMAC tokens (privacy contract): variant containment (default; = PSI-cardinality containment) or
    gcov_sig (chance-corrected, significance-weighted)."""
    rf = bundled_module("role_features")
    hs, ht = _hmac_sets(request["source_path"]), _hmac_sets(request["target_path"])
    variant = options.get("variant", "containment")
    if variant == "containment":
        return rf.psi_containment_scores(hs, ht)
    if variant == "gcov_sig":
        return rf.shared_hmac_genuine_scores(hs, ht)
    raise ValueError(f"hmac_containment variant must be containment or gcov_sig: {variant}")


def m_bottom_k(request: dict, options: dict) -> np.ndarray:
    """Bottom-k overlap sketches of the HMAC tokens (only k hashes per column are exchanged); k = 128 by default."""
    rf = bundled_module("role_features")
    k = int(options.get("k", 128))
    hs, ht = _hmac_sets(request["source_path"]), _hmac_sets(request["target_path"])
    return rf.bottom_k_containment_scores(rf.bottom_k_sketches(hs, k), rf.bottom_k_sketches(ht, k), k)


def _strict_columns(path: str):
    return bundled_module("role_tables").strict_table(Path(path))[1]


def m_afd_role(request: dict, options: dict) -> np.ndarray:
    """Within-table AFD roles (smx/afd_measures): measure gpdep (default), tau, mu_plus, g3n or rfi_plus; policy raw
    (default) or nonkey (pairs with a unique endpoint removed); 12-D role descriptor, 1 - mean L1 (role_scores)."""
    am, rr = bundled_module("afd_measures"), bundled_module("relational_roles")
    measure, policy = options.get("measure", "gpdep"), options.get("policy", "raw")
    if measure not in am.MEASURES or policy not in ("raw", "nonkey"):
        raise ValueError(f"afd_role: measure in {am.MEASURES}, policy raw or nonkey")
    rel = []
    for side in ("source", "target"):
        matrices, aux, _ = am.afd_matrices(_strict_columns(request[f"{side}_path"]), with_rfi=measure == "rfi_plus")
        rel.append(matrices[measure] * aux["nonkey"] if policy == "nonkey" else matrices[measure])
    return rr.role_scores(*rel)


def m_dependence_role(request: dict, options: dict) -> np.ndarray:
    """Within-table unary dependence roles (smx/dependence_corruption): measure binary_fd (exact unary FD), gpdep, u
    (uncertainty coefficient) or nmi (default binary_fd); policy raw (default) or nonkey; role_scores as above."""
    dm, rr = bundled_module("dependence_measures"), bundled_module("relational_roles")
    measure, policy = options.get("measure", "binary_fd"), options.get("policy", "raw")
    if measure not in dm.MEASURES or policy not in ("raw", "nonkey"):
        raise ValueError(f"dependence_role: measure in {dm.MEASURES}, policy raw or nonkey")
    rel = [dm.dependence_matrices(_strict_columns(request[f"{side}_path"]))[0][f"{policy}_{measure}"]
           for side in ("source", "target")]
    return rr.role_scores(*rel)


def m_order_role(request: dict, options: dict) -> np.ndarray:
    """Order role (harness/regime_matrix.py `order role`): per column, quantiles of the non-discordance mono(a -> b) over
    sampled row pairs (first 5,000 rows, seed 20260903 by default), compared by cosine."""
    cc, orl, oro, oc = (bundled_module(m) for m in ("class_columns", "order_relations", "order_roles", "order_constraints"))
    seed = int(options.get("seed", 20260903))
    profiles = []
    for side in ("source", "target"):
        names, cols = cc.read_columns(Path(request[f"{side}_path"]))
        prepared = [orl.to_ordered(c, False)[0] for c in cols]
        n = len(cols[0]) if cols else 0
        rng = np.random.default_rng(seed)
        k = min(60000, max(1000, n * 20))
        pi, pj = rng.integers(0, n, k), rng.integers(0, n, k)
        keep = pi != pj
        matrix = np.nan_to_num(orl.order_matrix(prepared, pi[keep], pj[keep]), nan=0.0)
        profiles.append(oro.role_profile(matrix, len(names)))
    return oc.cosine_similarity(*profiles)


def m_dc_role(request: dict, options: dict) -> np.ndarray:
    """Denial-constraint role (harness/od_role.py --relation dc): per column, degree, mean constraint length and equality
    share over the table's DCs, compared by cosine. source lima (default; dc.scored.json) or dcfinder (DC_results.txt)."""
    oc = bundled_module("order_constraints")
    source = options.get("source", "lima")
    fname, parse = {"lima": ("dc.scored.json", oc.parse_lima_dcs), "dcfinder": ("DC_results.txt", oc.parse_dcs)}[source]
    profiles = []
    for side in ("source", "target"):
        with open(request[f"{side}_path"], newline="", encoding="utf-8", errors="replace") as fh:
            cols = next(csv.reader(fh), [])
        profiles.append(oc.dc_profile(parse(table_metadata(request[f"{side}_path"], fname)), cols))
    return oc.cosine_similarity(*profiles)


METHODS = {
    "isresmat": m_isresmat,
    "word_vectors": m_word_vectors,
    "kang_naughton": m_kang_naughton,
    "kang_naughton_profile": lambda r, o: m_kang_naughton(r, o, profile=True),
    "char3_soft": lambda r, o: m_soft_overlap(r, o, "char3"),
    "token_soft": lambda r, o: m_soft_overlap(r, o, "token"),
    "semantic_types": m_semantic_types,
    "role_gcov": lambda r, o: m_role(r, o, "gcov"),
    "role_gcov_sig": lambda r, o: m_role(r, o, "gcov_sig"),
    "role_uncertainty": lambda r, o: m_role(r, o, "uncertainty"),
    "llm": m_llm,
    "sbert": m_sbert,
    "embedding_random": m_embedding_random,
    "valentine_distribution": m_valentine_distribution,
    "valentine_jaccard": m_valentine_jaccard,
    "coma": lambda r, o: m_coma(r, o, instances=False),
    "coma_instances": lambda r, o: m_coma(r, o, instances=True),
    "cupid": m_cupid,
    "magneto": m_magneto,
    "bdikit_cl": m_bdikit_cl,
    "snoopy": m_snoopy,
    "bdikit_max_val_sim": lambda r, o: m_magneto(r, {**o, "variant": "max_val_sim"}),
    "bdikit_two_phase": lambda r, o: m_magneto(r, {**o, "variant": "two_phase"}),
    "bdikit_two_phase_coma": lambda r, o: m_magneto(r, {**o, "variant": "two_phase", "second_stage": "coma"}),
    "ratcliff_obershelp": lambda r, o: m_simple(r, o, "ratcliff_obershelp"),
    "token_sort_ratio": lambda r, o: m_simple(r, o, "token_sort_ratio"),
    "token_set_ratio": lambda r, o: m_simple(r, o, "token_set_ratio"),
    "partial_ratio": lambda r, o: m_simple(r, o, "partial_ratio"),
    "editex": lambda r, o: m_simple(r, o, "editex"),
    "bag_distance": lambda r, o: m_simple(r, o, "bag_distance"),
    "tversky": lambda r, o: m_simple(r, o, "tversky"),
    "overlap_coefficient": lambda r, o: m_simple(r, o, "overlap_coefficient"),
    "generalized_jaccard": lambda r, o: m_simple(r, o, "generalized_jaccard"),
    "abbreviation": lambda r, o: m_simple(r, o, "abbreviation"),
    "tfidf_values": lambda r, o: m_simple(r, o, "tfidf_values"),
    "range_overlap": lambda r, o: m_simple(r, o, "range_overlap"),
    "value_length": lambda r, o: m_simple(r, o, "value_length"),
    "histogram_intersection": lambda r, o: m_simple(r, o, "histogram_intersection"),
    "bhattacharyya": lambda r, o: m_simple(r, o, "bhattacharyya"),
    "energy_distance": lambda r, o: m_simple(r, o, "energy_distance"),
    "topk_values": lambda r, o: m_simple(r, o, "topk_values"),
    "benford": lambda r, o: m_simple(r, o, "benford"),
    "digit_count": lambda r, o: m_simple(r, o, "digit_count"),
    "dumas": lambda r, o: m_simple(r, o, "dumas"),
    "lex_pmf": m_lex_pmf,
    "unary_key_role": m_unary_key_role,
    "gcov_pind": m_gcov_pind,
    "cross_ind": m_cross_ind,
    "hmac_containment": m_hmac_containment,
    "bottom_k": m_bottom_k,
    "afd_role": m_afd_role,
    "dependence_role": m_dependence_role,
    "binary_fd_role": lambda r, o: m_dependence_role(r, {**o, "measure": "binary_fd"}),
    "nmi_role": lambda r, o: m_dependence_role(r, {**o, "measure": "nmi"}),
    "order_role": m_order_role,
    "dc_role": m_dc_role,
}


DISABLED = {m.strip() for m in os.environ.get("SMX_DISABLED_METHODS", "").split(",") if m.strip()}


def compute(request: dict) -> np.ndarray:
    method = request.get("method")
    if method not in METHODS:
        raise KeyError(f"unknown method {method!r}; available: {sorted(METHODS)}")
    if method in DISABLED:
        # fails the seed at once instead of running it (e.g. ISResMat while it is paused)
        raise PermissionError(f"method {method!r} is disabled on this server (SMX_DISABLED_METHODS)")
    S = np.asarray(METHODS[method](request, request.get("options") or {}), dtype=float)
    want = (len(request["source_labels"]), len(request["target_labels"]))
    if S.shape != want:
        raise ValueError(f"{method} returned shape {S.shape}, expected {want}")
    if not np.all(np.isfinite(S)):
        raise ValueError(f"{method} returned non-finite scores")
    return S


def render(S: np.ndarray) -> str:
    return "\n".join(" ".join(f"{v:.9g}" for v in row) for row in S) + "\n"


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.1 keeps the JVM's connection open. With the default HTTP/1.0 the server closes it after
    # every answer, and Java's HttpClient sometimes sends the next request on the closed pooled
    # connection ("header parser received no bytes"): rare with one request per scenario, frequent
    # with the dozens of table pairs of a multi-table scenario. Every response sets Content-Length.
    protocol_version = "HTTP/1.1"

    def _send(self, status: int, text: str) -> None:
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/") == "/health":
            self._send(200, "\n".join(sorted(METHODS)) + "\n")
        else:
            self._send(404, "not found\n")

    def do_POST(self):  # noqa: N802
        if self.path.rstrip("/") != "/match":
            self._send(404, "not found\n")
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            request = json.loads(self.rfile.read(length).decode("utf-8"))
            self._send(200, render(compute(request)))
        except Exception as exc:  # reported to the Java side, which fails loudly
            traceback.print_exc()
            self._send(500, f"{type(exc).__name__}: {exc}\n")

    def log_message(self, fmt, *args):  # quieter than the default per-request line
        sys.stderr.write("seed_server: " + (fmt % args) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5101)
    args = ap.parse_args()
    server = HTTPServer(("127.0.0.1", args.port), Handler)  # --port 0: any free port, printed below
    print(f"seed_server listening on 127.0.0.1:{server.server_address[1]}; methods: {', '.join(sorted(METHODS))}",
          flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
