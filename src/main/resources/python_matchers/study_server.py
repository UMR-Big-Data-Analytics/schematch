"""Scenario-level matcher server using implementations bundled with Schematch.
Run: python src/main/resources/python_matchers/study_server.py --port 5102
Dependencies: requirements.txt. No neighboring repositories are imported.
"""
from __future__ import annotations

import argparse
import json
import sys
import traceback
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import os

import numpy as np

from schematch_py import dependency as score_xtest
from schematch_py import multitable as x105
from schematch_py import entities as x109
from schematch_py import holistic
from schematch_py import omnimatch_release
from schematch_py import rema
from schematch_py.composite import load, unit

IV = ("coma", "char3_soft", "cupid", "sbert_mpnet")
VAL = ("unary_statistics_invariant", "pmf", "unary_statistics")


def norm_label(label: str) -> str:
    """'table.csv.column' -> 'table.column'; 'table.column' unchanged."""
    return x105.lab(label) if ".csv." in label else label


def split_scenario(path: str):
    p = Path(path).resolve()
    return p.parent.parent, p.parent.name, p.name


def compose_base(rt, ds, sc, rows, cols, options):
    recipe = options.get("base_recipe")
    seed_root = options.get("seed_root")
    if not recipe or not seed_root:
        raise ValueError("no base sent and no options.base_recipe / options.seed_root given")
    sd = Path(seed_root)
    names = {"iv": IV, "iv+0.1val": IV + VAL, "c3": ("char3_soft", "jsd_values") + VAL, "val": VAL}[recipe]
    U = {}
    for m in names:
        M = load(sd / m / ds / sc / "seed.csv", rows, cols)
        if M is None or M.shape != (len(rows), len(cols)):
            raise FileNotFoundError(f"seed {m} missing or misaligned for {ds}/{sc} under {sd}")
        U[m] = unit(M)
    V = np.mean([U[m] for m in VAL], axis=0) if all(m in U for m in VAL) else None
    if recipe == "iv":
        return np.mean([U[m] for m in IV], axis=0)
    if recipe == "iv+0.1val":
        return np.mean([U[m] for m in IV], axis=0) + 0.1 * V
    if recipe == "c3":
        return np.mean([U["char3_soft"], U["jsd_values"], V], axis=0)
    return V


def m_base(ctx, options):
    return ctx["base"]


def m_q(ctx, options):
    return ctx["base"] + float(options.get("q_weight", 0.5)) * x105.q_term(ctx["rows"], ctx["cols"])


def m_dep(ctx, options):
    M = score_xtest.x105_dep(ctx["rt"], None, ctx["ds"], ctx["sc"], ctx["rows"], ctx["cols"], ctx["base"])
    w = float(options.get("q_weight", 0.0))
    return M + w * x105.q_term(ctx["rows"], ctx["cols"]) if w else M


def m_entity_alignment(ctx, options):
    virtual = bool(options.get("virtual", False))
    key_equiv = bool(options.get("key_equiv", True))
    gs = x109.side_graph(ctx["rt"], ctx["ds"], ctx["sc"], "source", ctx["rows"], key_equiv, virtual)
    gt = x109.side_graph(ctx["rt"], ctx["ds"], ctx["sc"], "target", ctx["cols"], key_equiv, virtual)
    cell = {"rows": ctx["rows"], "cols": ctx["cols"], "base": ctx["base"], "fds_s": {}, "fds_t": {}}
    M, _ = x109.output(cell, gs, gt, float(options.get("lam", 0.1)), float(options.get("mu", 0.1)), False,
                       greedy=bool(options.get("greedy", True)))
    return M


def m_alite(ctx, options):
    return holistic.alite(ctx, options)


def m_ract(ctx, options):
    return holistic.ract(ctx, options)


def m_omnimatch(ctx, options):
    return holistic.omnimatch(ctx, options)


def m_omnimatch_release(ctx, options):
    return omnimatch_release.omnimatch(ctx, options)


def m_rema(ctx, options):
    return rema.rema(ctx, options)


def m_pded(ctx, options):
    """PDED (graduated-assignment matching of the two FD graphs) through PDED's own runner, unchanged:
    de.cleaning.integration.PdedSchematchRunner on a temporary copy of this scenario, distribution weight options.weight
    (default 0.65, as the harness tables). Needs options.pded_home or SCHEMATCH_PDED (a built PDED: target/classes plus
    cp.txt) and options.java or SCHEMATCH_JAVA. PDED reads one CSV per side and its own FD lines; its FD pattern requires a
    right-hand side without spaces, so FDs on such columns are skipped by PDED itself."""
    import csv
    import shutil
    import subprocess
    import tempfile
    configured_home = options.get("pded_home") or os.environ.get("SCHEMATCH_PDED")
    if not configured_home:
        raise ValueError("PDED requires options.pded_home or SCHEMATCH_PDED pointing to an explicitly installed build")
    home = Path(configured_home).expanduser().resolve()
    classes, cp_file = home / "target" / "classes", home / "cp.txt"
    if not classes.is_dir() or not cp_file.is_file():
        raise FileNotFoundError(f"no PDED build at {home} (needs target/classes and cp.txt)")
    java = options.get("java") or os.environ.get("SCHEMATCH_JAVA") or "java"
    weight = float(options.get("weight", 0.65))
    scenario = ctx["rt"] / ctx["ds"] / ctx["sc"]
    with tempfile.TemporaryDirectory(prefix="pded_") as tmp:
        data, out = Path(tmp) / "data", Path(tmp) / "out"
        for part in ("source", "target", "metadata"):
            if (scenario / part).is_dir():
                shutil.copytree(scenario / part, data / ctx["sc"] / part)
        subprocess.run([java, "-Xmx4g", "-cp", os.pathsep.join([str(classes), cp_file.read_text().strip()]),
                        "de.cleaning.integration.PdedSchematchRunner", str(data), str(out), repr(weight)],
                       cwd=home, check=True, capture_output=True, timeout=int(options.get("timeout", 3600)))
        with (out / ctx["sc"] / "pded.csv").open(newline="", encoding="utf-8") as fh:
            rows = list(csv.reader(fh))
    bare = lambda x: x.split(".")[-1]  # noqa: E731
    ci = {bare(c): j for j, c in enumerate(rows[0][1:])}
    ri = {bare(r[0]): i for i, r in enumerate(rows[1:])}
    M = np.array([[float(x) for x in r[1:]] for r in rows[1:]])
    if not all(bare(r) in ri for r in ctx["rows"]) or not all(bare(c) in ci for c in ctx["cols"]):
        raise ValueError("PDED output labels do not match the scenario's columns")
    return M[np.ix_([ri[bare(r)] for r in ctx["rows"]], [ci[bare(c)] for c in ctx["cols"]])]


METHODS = {"base": m_base, "q": m_q, "dep": m_dep, "entity_alignment": m_entity_alignment,
           "alite": m_alite, "ract": m_ract, "omnimatch": m_omnimatch, "omnimatch_release": m_omnimatch_release,
           "pded": m_pded, "rema": m_rema}
# Standalone matchers: they read the scenario's tables themselves and need no base (a sent base is ignored).
BASE_FREE = {"alite", "ract", "omnimatch", "omnimatch_release", "pded", "rema"}


def compute(request: dict) -> np.ndarray:
    method = request.get("method")
    if method not in METHODS:
        raise KeyError(f"unknown method {method!r}; available: {sorted(METHODS)}")
    options = request.get("options") or {}
    rt, ds, sc = split_scenario(request["scenario_path"])
    rows = [norm_label(x) for x in request["source_labels"]]
    cols = [norm_label(x) for x in request["target_labels"]]
    if method in BASE_FREE:
        base = np.zeros((len(rows), len(cols)))
    elif request.get("base") is not None:
        base = np.asarray(request["base"], dtype=float)
        if base.shape != (len(rows), len(cols)):
            raise ValueError(f"base has shape {base.shape}, expected {(len(rows), len(cols))}")
    else:
        base = compose_base(rt, ds, sc, rows, cols, options)
    base = np.nan_to_num(base)
    # Order and ties: the study's methods break ties by column index (Hungarian, argsort, greedy order), and the hashed
    # variants keep the original column order, which correlates with the truth (X109 test tie check). By default the
    # wrapper therefore computes in a canonical order (labels sorted) and breaks ties with a tiny seeded noise, so the
    # output does not depend on the order in which Schematch lists the columns. canonical=false, tie_noise=0 reproduces
    # the registered study numbers exactly.
    canonical = bool(options.get("canonical", True))
    pr = np.argsort(rows, kind="stable") if canonical else np.arange(len(rows))
    pc = np.argsort(cols, kind="stable") if canonical else np.arange(len(cols))
    base_c = base[np.ix_(pr, pc)]
    noise = float(options.get("tie_noise", 1e-7))
    if noise > 0:
        base_c = base_c + noise * np.random.default_rng(int(options.get("tie_seed", 0))).random(base_c.shape)
    ctx = {"rt": rt, "ds": ds, "sc": sc, "rows": [rows[i] for i in pr], "cols": [cols[j] for j in pc], "base": base_c}
    S_c = np.asarray(METHODS[method](ctx, options), dtype=float)
    if S_c.shape != (len(rows), len(cols)):
        raise ValueError(f"{method} returned shape {S_c.shape}, expected {(len(rows), len(cols))}")
    S = np.empty_like(S_c)
    S[np.ix_(pr, pc)] = S_c
    if not np.all(np.isfinite(S)):
        raise ValueError(f"{method} returned non-finite scores")
    return S


def render(S: np.ndarray) -> str:
    return "\n".join(" ".join(f"{v:.9g}" for v in row) for row in S) + "\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive, as seed_server.py (Java's HttpClient reuses connections)

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

    def log_message(self, fmt, *args):
        sys.stderr.write("study_server: " + (fmt % args) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=5102)
    args = ap.parse_args()
    server = HTTPServer(("127.0.0.1", args.port), Handler)
    print(f"study_server listening on 127.0.0.1:{server.server_address[1]}; methods: {', '.join(sorted(METHODS))}", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
