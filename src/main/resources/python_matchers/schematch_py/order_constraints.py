"""Vendored from harness/od_role.py (see SOURCES.json); do not edit, re-vendor instead."""
from __future__ import annotations
import json
import re
from collections import defaultdict
from pathlib import Path
from statistics import mean

import numpy as np


OD_RE = re.compile(r"^\[(.*)\]\s*~>\s*(.+)$")


DC_PRED_RE = re.compile(r"t[01]\.([A-Za-z0-9_]+)\s*(==|<>|<=|>=|<|>)")


def parse_ods(path: Path):
    """(lhs columns, rhs columns) pairs, bare column names."""
    out = []
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = OD_RE.match(line.strip())
        if not m:
            continue
        lhs = [c.strip().split(".")[-1] for c in m.group(1).split(",") if c.strip()]
        rhs = [c.strip().split(".")[-1] for c in m.group(2).split(",") if c.strip()]
        if lhs and rhs:
            out.append((lhs, rhs))
    return out


def parse_dcs(path: Path):
    """Per denial constraint, the columns it mentions and its predicate count.

    A DC is a forbidden combination of predicates over a tuple pair, so it has
    no left/right side to mirror the OD descriptor with. The projection used
    here is deliberately the simplest defensible one -- which columns a
    constraint constrains, and how many predicates it takes to do so -- rather
    than whichever projection scores best.
    """
    out = []
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        cols = DC_PRED_RE.findall(line)
        if not cols:
            continue
        names = [c for c, _ in cols]
        equalities = sum(1 for _, op in cols if op == "==")
        out.append((names, len(cols), equalities))
    return out


def parse_lima_dcs(path: Path):
    """Read the canonical LIMA cache projection, preserving arbitrary labels."""
    if not path.is_file():
        return []
    artifact = json.loads(path.read_text(encoding="utf-8"))
    labels = {column["id"]: column["label"] for column in artifact["spec"]["columns"]}
    out = []
    for record in artifact["records"]:
        names = [labels[predicate["column"]] for predicate in record["predicates"]]
        equalities = sum(predicate["operator"] == "=" for predicate in record["predicates"])
        out.append((names, len(record["predicates"]), equalities))
    return out


def dc_profile(dcs, columns):
    """Degree, mean constraint length, and equality share, per column."""
    n = max(1, len(columns))
    index = {c: i for i, c in enumerate(columns)}
    deg = np.zeros(len(columns))
    lengths = defaultdict(list)
    eq_share = defaultdict(list)
    for names, size, equalities in dcs:
        for c in set(names):
            if c in index:
                i = index[c]
                deg[i] += 1
                lengths[i].append(size)
                eq_share[i].append(equalities / size if size else 0.0)
    prof = np.zeros((len(columns), 3))
    prof[:, 0] = deg / n
    for i in range(len(columns)):
        prof[i, 1] = mean(lengths[i]) / n if lengths[i] else 0.0
        prof[i, 2] = mean(eq_share[i]) if eq_share[i] else 0.0
    return prof


def od_profile(ods, columns):
    """Outgoing degree, incoming degree, mean arity, per column."""
    n = max(1, len(columns))
    index = {c: i for i, c in enumerate(columns)}
    out_deg = np.zeros(len(columns))
    in_deg = np.zeros(len(columns))
    arity = defaultdict(list)
    for lhs, rhs in ods:
        for c in lhs:
            if c in index:
                out_deg[index[c]] += 1
                arity[index[c]].append(len(lhs))
        for c in rhs:
            if c in index:
                in_deg[index[c]] += 1
                arity[index[c]].append(len(rhs))
    prof = np.zeros((len(columns), 3))
    prof[:, 0] = out_deg / n
    prof[:, 1] = in_deg / n
    for i in range(len(columns)):
        prof[i, 2] = mean(arity[i]) / n if arity[i] else 0.0
    return prof


def cosine_similarity(a, b):
    na = np.linalg.norm(a, axis=1, keepdims=True)
    nb = np.linalg.norm(b, axis=1, keepdims=True)
    na[na == 0] = 1.0
    nb[nb == 0] = 1.0
    return (a / na) @ (b / nb).T


def scale_align(base, extra, weight):
    """Range-align `extra` to `base` before blending, as the pipeline does."""
    lo, hi = float(base.min()), float(base.max())
    if hi <= lo:
        return base
    e_lo, e_hi = float(extra.min()), float(extra.max())
    scaled = (np.full_like(extra, 0.5) if e_hi <= e_lo
              else (extra - e_lo) / (e_hi - e_lo))
    return (1.0 - weight) * base + weight * (lo + scaled * (hi - lo))
