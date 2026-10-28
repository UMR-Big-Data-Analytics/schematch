"""MaDI and GOBY pairwise conversion through their published unified-schema mappings.
GOBY eligibility, seed and column filtering preserve the study construction."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import shutil
import sys
from collections import Counter
from itertools import combinations
from pathlib import Path

from .external import dedupe, read_csv, read_dicts, write_scenario

DS_NAME = {"madi": "MADI", "goby": "GOBY"}
SEED, GOBY_PAIRS, GOBY_MIN_ROWS, GOBY_MAX_ROWS, GOBY_MIN_COLS = 20261006, 200, 20, 50_000, 3


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def cell(v) -> str:
    if v is None:
        return ""
    if isinstance(v, list):
        return "; ".join(cell(x) for x in v)
    if isinstance(v, dict):
        return json.dumps(v, ensure_ascii=False, sort_keys=True)
    return str(v)


def read_records(p: Path) -> tuple[list[str], list[list[str]]]:
    text = p.read_text(encoding="utf-8")
    recs = [json.loads(line) for line in text.split("\n") if line.strip()] if p.suffix == ".jsonl" else json.loads(text)
    hdr: list[str] = []
    for r in recs:
        hdr += [k for k in r if k not in hdr]
    return hdr, [[cell(r.get(k)) for k in hdr] for r in recs]


def pairwise(sh, th, tags_s: dict[str, set], tags_t: dict[str, set]) -> list[tuple[str, str]]:
    return [(a, b) for a in sh for b in th if tags_s.get(a, set()) & tags_t.get(b, set())]


def madi(root: Path, man: dict, base: Path) -> None:
    for dom in ("companies", "games", "music", "papers", "products"):
        inp = base / dom / "base" / "input"
        gold_p = inp / "schemamatching" / "sm_mapping_gold.json"
        gold = json.loads(gold_p.read_text(encoding="utf-8"))["mappings"]
        man["truth_sha256"][f"{dom}/sm_mapping_gold.json"] = sha(gold_p)
        files = {}
        for p in sorted((inp / "data").iterdir()):
            if p.name.endswith("_metadata.json") or p.suffix not in (".csv", ".jsonl", ".json"):
                continue
            name = p.stem
            if dom == "products":          # dataset_<k>.json is named products_<k> in the gold file
                name = "products_" + p.stem.split("_")[1]
            files[name] = p
        tables = {}
        for name, p in files.items():
            h, r = read_csv(p) if p.suffix == ".csv" else read_records(p)
            if len(set(h)) != len(h):
                raise SystemExit(f"{p}: duplicate column names")
            tables[name] = (h, r)
            man["truth_sha256"][f"{dom}/data/{p.name}"] = sha(p)
        tags = {n: {} for n in tables}
        for g in gold:
            if g.get("label") is not True:
                continue
            s, c = g["source_dataset"], g["source_column"]
            if s not in tables or c not in tables[s][0]:
                raise SystemExit(f"{dom}: gold names {s}.{c}, which is not in the tables")
            tags[s].setdefault(c, set()).add(g["target_column"])
        for n, (h, _) in tables.items():
            unm = [c for c in h if c not in tags[n]]
            if unm:
                man["notes"].append(f"{dom}/{n}: {len(unm)} of {len(h)} columns not in the gold file (non-matches): {unm}")
        if dom == "products":
            man["notes"].append("products: the four sources are renamed variants of one WDC Products sample (metadata: "
                                "'Source-Schema Variant k'), not independently designed schemas")
        for a, b in combinations(sorted(tables), 2):
            (sh, sr), (th, tr) = tables[a], tables[b]
            matches = pairwise(sh, th, tags[a], tags[b])
            sc = f"{dom}__{a}__{b}"
            if not matches:
                man["skipped"].append(f"{sc}: no shared unified attribute")
                continue
            write_scenario(root, sc, sh, sr, th, tr, matches, man)
            man["scenarios"][sc]["domain"] = dom
            man["scenarios"][sc]["independent"] = dom != "products"   # B 2026-10-06: products reported separately


def goby(root: Path, man: dict, base: Path) -> None:
    for f in ("goby_schema_mappings.csv", "unified_schema.csv", "goby_data_csv_files.tar.gz"):
        man["truth_sha256"][f] = sha(base / f)
    maps = read_dicts(base / "goby_schema_mappings.csv")
    tags: dict[str, dict[str, set]] = {}
    for r in maps:
        tags.setdefault(r["wrapper_id"], {}).setdefault(r["field_name"], set()).add(r["tag_code"].strip())
    unified = {r["tag_code"].strip() for r in read_dicts(base / "unified_schema.csv")}
    unknown = sorted({t for w in tags.values() for s in w.values() for t in s} - unified - {"NO_DISPLAY"})
    if unknown:
        raise SystemExit(f"GOBY tags not in unified_schema.csv: {unknown}")
    elig, drop = {}, Counter()
    for p in sorted((base / "wrappers").glob("goby_*.csv")):
        wid = p.stem[5:]
        h, r = read_csv(p)
        if len(set(h)) != len(h):
            raise SystemExit(f"{p}: duplicate column names")
        wt = tags.get(wid, {})
        miss = [c for c in wt if c not in h]
        if miss:
            raise SystemExit(f"{p.name}: mapping names absent columns {miss}")
        keep = [j for j, c in enumerate(h) if wt.get(c, set()) - {"NO_DISPLAY"}]
        drop["untagged"] += sum(1 for c in h if c not in wt)
        drop["no_display"] += sum(1 for c in h if c in wt and not wt[c] - {"NO_DISPLAY"})
        drop["kept"] += len(keep)
        if not (GOBY_MIN_ROWS <= len(r) <= GOBY_MAX_ROWS and len(keep) >= GOBY_MIN_COLS):
            drop["ineligible_wrappers"] += 1
            continue
        elig[wid] = p, keep
    man["column_filter"] = dict(drop)
    man["eligible_wrappers"] = len(elig)
    ids = sorted(elig, key=int)
    rng = random.Random(SEED)
    n_pairs = len(ids) * (len(ids) - 1) // 2
    chosen: set[tuple[str, str]] = set()
    while len(chosen) < min(GOBY_PAIRS, n_pairs):
        a, b = rng.sample(ids, 2)
        if (a, b) in chosen or (b, a) in chosen:
            continue
        chosen.add((a, b))           # rng.sample's order is the random source/target order
    man["eligible_pairs"] = n_pairs
    cache: dict[str, tuple[list[str], list[list[str]]]] = {}

    def load(wid):
        if wid not in cache:
            p, keep = elig[wid]
            h, r = read_csv(p)
            cache[wid] = ([h[j] for j in keep], [[row[j] if j < len(row) else "" for j in keep] for row in r])
        return cache[wid]
    for a, b in sorted(chosen, key=lambda t: (int(t[0]), int(t[1]))):
        (sh, sr), (th, tr) = load(a), load(b)
        ta = {c: tags[a][c] - {"NO_DISPLAY"} for c in sh}
        tb = {c: tags[b][c] - {"NO_DISPLAY"} for c in th}
        matches = pairwise(sh, th, ta, tb)
        sc = f"goby_{a}__goby_{b}"
        write_scenario(root, sc, dedupe(sh), sr, dedupe(th), tr, matches, man)
        man["scenarios"][sc]["shared_attributes"] = len({t for c in sh for t in ta[c]} & {t for c in th for t in tb[c]})
    man["notes"].append(f"{sum(1 for v in man['scenarios'].values() if v['matches'] == 0)} sampled pairs share no "
                        f"unified attribute (kept: all-negative scenarios)")
